package com.lootbag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.events.ConfigChanged;
import java.awt.image.BufferedImage;

@PluginDescriptor(
	name = "Lootbag",
	description = "Track your loot and bank value",
	tags = { "grand exchange", "loot", "bank", "value", "flipping", "GE", "GE tracker", "merching", "merch" }
)
public class LootbagPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(LootbagPlugin.class);
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final int DEBOUNCE_DELAY_MS = 3000; // Wait 3 seconds after last change

	@Inject
	private Client client;

	@Inject
	private LootbagConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;



	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private LootbagPanel panel;

	@Inject
	private ItemManager itemManager;

	private NavigationButton navButton;



	private String cachedJwtToken;
	private long tokenExpiryTime;
	private Map<Integer, Integer> pendingBankItems;
	private long lastBankChangeTime;
	private boolean syncScheduled;
	private Map<String, Integer> lastSyncedBankState;
	private boolean hasSyncedThisSession = false;
	private Map<Integer, GrandExchangeOffer> lastGEOffers = new HashMap<>();
	private final java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

	@Override
	protected void startUp()
	{
		log.debug("Lootbag started!");
		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/lootbag.png");
		}
		catch (Exception e)
		{
			log.warn("Failed to load /lootbag.png, falling back to default icon");
			try
			{
				icon = itemManager.getImage(ItemID.LOOTING_BAG);
			}
			catch (Exception ex)
			{
				log.warn("Failed to load ItemID.LOOTING_BAG icon", ex);
				icon = null;
			}
		}

		if (icon == null)
		{
			log.warn("Using blank fallback icon");
			icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		}

		navButton = NavigationButton.builder()
			.tooltip("Lootbag")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);


		cachedJwtToken = null;
		tokenExpiryTime = 0;
		pendingBankItems = null;
		lastBankChangeTime = 0;
		syncScheduled = false;

		// Initialize panel and attempt auto-login if credentials exist
		panel.init(this);
		
		// Try to auto-login if we have saved credentials
		String username = config.username();
		String password = config.password();
		if (username != null && !username.isEmpty() && password != null && !password.isEmpty())
		{
			log.debug("Found saved credentials, attempting auto-login...");
			login(username, password, (success, message) -> {
				if (success)
				{
					log.debug("Auto-login successful");
				}
				else
				{
					log.warn("Auto-login failed: {}", message);
				}
			});
		}
	}

	@Override
	protected void shutDown()
	{
		log.debug("Lootbag shutting down...");
		
		// Sync bank on shutdown if we have data and are logged in
		if (pendingBankItems != null && cachedJwtToken != null)
		{
			log.info("Initiating final bank sync before shutdown...");
			final Map<Integer, Integer> itemsToSync = pendingBankItems;
			final String tokenToUse = cachedJwtToken;
			final long bankValue = calculateBankValue(pendingBankItems);
			
			// Fire and forget - don't block shutdown
			submitBankData(itemsToSync, tokenToUse, bankValue);
		}
		
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		cachedJwtToken = null;
		tokenExpiryTime = 0;
		pendingBankItems = null;
		syncScheduled = false;
		hasSyncedThisSession = false;
		executor.shutdown();
	}
	
	public boolean isLoggedIn()
	{
		return cachedJwtToken != null && System.currentTimeMillis() < tokenExpiryTime;
	}

	public void logout()
	{
		cachedJwtToken = null;
		tokenExpiryTime = 0;
		configManager.unsetConfiguration("lootbag", "username");
		configManager.unsetConfiguration("lootbag", "password");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Sync on logout
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			if (pendingBankItems != null && cachedJwtToken != null && hasSyncedThisSession)
			{
				log.info("Player logged out, performing final bank sync...");
				final Map<Integer, Integer> itemsToSync = pendingBankItems;
				final String tokenToUse = cachedJwtToken;
				final long bankValue = calculateBankValue(pendingBankItems);
				
				// Fire and forget - don't block
				submitBankData(itemsToSync, tokenToUse, bankValue);
			}
			hasSyncedThisSession = false;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK.getId())
		{
			return;
		}

		// Update the pending container
		Map<Integer, Integer> snapshot = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item.getId() != -1)
			{
				snapshot.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}

		// Add inventory gold (Coins) to the snapshot if present
		// This ensures we capture total liquid wealth
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() == ItemID.COINS)
				{
					snapshot.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		
		pendingBankItems = snapshot;
		
		// Only sync on first bank open of the session
		if (!hasSyncedThisSession && !syncScheduled)
		{
			log.info("First bank open this session, scheduling sync...");
			syncScheduled = true;
			hasSyncedThisSession = true;
			lastBankChangeTime = System.currentTimeMillis();
			scheduleDebouncedSync();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();
		
		// Create snapshot of CURRENT state
		SnapshotGEOffer currentSnapshot = new SnapshotGEOffer(
			offer.getState(), 
			offer.getItemId(), 
			offer.getQuantitySold(), 
			offer.getSpent(), 
			offer.getTotalQuantity(), 
			offer.getPrice()
		);

		// Check for transition to EMPTY (User collected items/cancelled)
		if (offer.getState() == GrandExchangeOfferState.EMPTY) 
		{
			GrandExchangeOffer previous = lastGEOffers.get(slot);
			if (previous != null) 
			{
				// Check if it was a valid trade (items sold > 0)
				// This handles BOUGHT, SOLD, and CANCELLED (partial)
				if (previous.getQuantitySold() > 0) 
				{
					// Only process if we're logged in
					if (cachedJwtToken != null)
					{
						// Calculate price per item for logging
						long pricePerItem = previous.getSpent() / Math.max(1, previous.getQuantitySold());
						log.info("GE trade collected: {} {} x{} for {} gp each (Total: {})", 
							previous.getState() == GrandExchangeOfferState.BOUGHT ? "BOUGHT" : 
							previous.getState() == GrandExchangeOfferState.SOLD ? "SOLD" : "CANCELLED",
							previous.getItemId(), 
							previous.getQuantitySold(),
							pricePerItem,
							previous.getSpent());

						// Submit previous
						// Fire and forget
						submitGETrade(previous);
					}
					else
					{
						log.debug("Skipping GE trade sync (collected) - not logged in");
					}
				}
			}
		}
		
		// Update cache with current snapshot
		lastGEOffers.put(slot, currentSnapshot);
	}

	private void scheduleDebouncedSync()
	{
		// Calculate bank value on the client thread
		final Map<Integer, Integer> bankItems = pendingBankItems;
		final long bankValue = calculateBankValue(bankItems);
		
		executor.schedule(() -> {
			long timeSinceLastChange = System.currentTimeMillis() - lastBankChangeTime;
			
			if (timeSinceLastChange >= DEBOUNCE_DELAY_MS)
			{
				// User has stopped making changes, sync now
				log.info("Bank changes settled, initiating sync...");
				authenticateAndSubmit(bankItems, bankValue);
				syncScheduled = false;
			}
			else
			{
				// Still waiting for changes to settle, reschedule check
				scheduleDebouncedSync();
			}
		}, 100, TimeUnit.MILLISECONDS);
	}

	private long calculateBankValue(Map<Integer, Integer> bankItems)
	{
		if (bankItems == null)
		{
			return 0;
		}

		long totalValue = 0;
		for (Map.Entry<Integer, Integer> entry : bankItems.entrySet())
		{
			int itemId = entry.getKey();
			int quantity = entry.getValue();
			int itemPrice = itemManager.getItemPrice(itemId);
			totalValue += (long) itemPrice * quantity;
		}
		return totalValue;
	}

	public void login(String username, String password, java.util.function.BiConsumer<Boolean, String> callback)
	{
		String authServerUrl = config.authServerUrl();

		if (authServerUrl == null || authServerUrl.isEmpty())
		{
			callback.accept(false, "Auth URL missing");
			return;
		}

		Map<String, String> loginData = new HashMap<>();
		loginData.put("email", username);
		loginData.put("password", password);

		String jsonPayload = gson.toJson(loginData);

		Request request = new Request.Builder()
			.url(authServerUrl)
			.post(RequestBody.create(JSON, jsonPayload))
			.header("Content-Type", "application/json")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.accept(false, "Connection failed");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						callback.accept(false, "Invalid credentials");
						return;
					}

					String responseBody = response.body().string();
					JsonParser parser = new JsonParser();
					JsonObject jsonResponse = parser.parse(responseBody).getAsJsonObject();

					if (jsonResponse.has("token"))
					{
						String token = jsonResponse.get("token").getAsString();
						cachedJwtToken = token;
						tokenExpiryTime = System.currentTimeMillis() + (50 * 60 * 1000);

						// Save credentials to config
						configManager.setConfiguration("lootbag", "username", username);
						configManager.setConfiguration("lootbag", "password", password);

						// Refresh panel to show logged-in state
						panel.refresh();

						callback.accept(true, "Success");
						
						// Trigger immediate sync if we have data
						if (pendingBankItems != null)
						{
							long bankValue = calculateBankValue(pendingBankItems);
							submitBankData(pendingBankItems, token, bankValue);
						}
					}
					else
					{
						callback.accept(false, "No token received");
					}
				}
				catch (Exception e)
				{
					callback.accept(false, "Error parsing response");
				}
			}
		});
	}

	private void authenticateAndSubmit(Map<Integer, Integer> bankItems, long bankValue)
	{
		String username = config.username();
		String password = config.password();
		String authServerUrl = config.authServerUrl();

		log.info("Authenticating with username: {}, authServerUrl: {}", username, authServerUrl);

		if (username == null || username.isEmpty() || password == null || password.isEmpty())
		{
			log.warn("Username or password not configured");
			return;
		}

		if (authServerUrl == null || authServerUrl.isEmpty())
		{
			log.warn("Auth server URL not configured");
			return;
		}

		// Check if we have a valid cached token
		if (cachedJwtToken != null && System.currentTimeMillis() < tokenExpiryTime)
		{
			log.debug("Using cached JWT token");
			submitBankData(bankItems, cachedJwtToken, bankValue);
			return;
		}

		// Re-use login logic but internally
		Map<String, String> loginData = new HashMap<>();
		loginData.put("email", username);
		loginData.put("password", password);

		String jsonPayload = gson.toJson(loginData);

		Request request = new Request.Builder()
			.url(authServerUrl)
			.post(RequestBody.create(JSON, jsonPayload))
			.header("Content-Type", "application/json")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Authentication failed", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						log.error("Authentication failed with status: {}", response.code());
						return;
					}

					String responseBody = response.body().string();
					JsonParser parser = new JsonParser();
					JsonObject jsonResponse = parser.parse(responseBody).getAsJsonObject();
					
					if (jsonResponse.has("token"))
					{
						String token = jsonResponse.get("token").getAsString();
						cachedJwtToken = token;
						tokenExpiryTime = System.currentTimeMillis() + (50 * 60 * 1000);
						submitBankData(bankItems, token, bankValue);
					}
				}
				catch (Exception e)
				{
					log.error("Failed to parse authentication response", e);
				}
			}
		});
	}

	private Map<String, Object> computeBankDelta(Map<String, Integer> currentBank)
	{
		Map<String, Object> delta = new HashMap<>();
		
		// If no previous state, this is a full sync
		if (lastSyncedBankState == null || lastSyncedBankState.isEmpty())
		{
			delta.put("type", "full");
			delta.put("data", currentBank);
			return delta;
		}

		Map<String, Integer> added = new HashMap<>();
		Map<String, Integer> updated = new HashMap<>();
		Map<String, Integer> removed = new HashMap<>();

		// Find added and updated items
		for (Map.Entry<String, Integer> entry : currentBank.entrySet())
		{
			String itemId = entry.getKey();
			Integer currentQty = entry.getValue();
			Integer previousQty = lastSyncedBankState.get(itemId);

			if (previousQty == null)
			{
				// New item
				added.put(itemId, currentQty);
			}
			else if (!currentQty.equals(previousQty))
			{
				// Quantity changed
				updated.put(itemId, currentQty);
			}
		}

		// Find removed items
		for (String itemId : lastSyncedBankState.keySet())
		{
			if (!currentBank.containsKey(itemId))
			{
				removed.put(itemId, lastSyncedBankState.get(itemId));
			}
		}

		// If nothing changed, return null to skip sync
		if (added.isEmpty() && updated.isEmpty() && removed.isEmpty())
		{
			return null;
		}

		delta.put("type", "delta");
		delta.put("added", added);
		delta.put("updated", updated);
		delta.put("removed", removed);
		
		return delta;
	}

	private String computeBankChecksum(Map<String, Integer> bankData)
	{
		// Create a sorted string representation for consistent hashing
		StringBuilder sb = new StringBuilder();
		bankData.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> sb.append(entry.getKey()).append(":").append(entry.getValue()).append(";"));
		
		// Simple hash for now - could use MD5/SHA256 if needed
		return String.valueOf(sb.toString().hashCode());
	}

	private void submitBankData(Map<Integer, Integer> bankItems, String jwtToken, long bankValue)
	{
		String syncServerUrl = config.syncServerUrl();

		if (syncServerUrl == null || syncServerUrl.isEmpty())
		{
			log.warn("Sync server URL not configured");
			return;
		}

		// Get the player's RSN from the client
		String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (rsn == null || rsn.isEmpty())
		{
			log.warn("Unable to determine player RSN, skipping sync");
			return;
		}

		// Build current bank state
		Map<String, Integer> bankData = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : bankItems.entrySet())
		{
			bankData.put(String.valueOf(entry.getKey()), entry.getValue());
		}

		// Compute delta
		Map<String, Object> delta = computeBankDelta(bankData);
		
		// Skip sync if nothing changed
		if (delta == null)
		{
			log.info("No bank changes detected, skipping sync");
			return;
		}

		// Compute checksum for integrity verification
		String checksum = computeBankChecksum(bankData);

		// Build payload
		Map<String, Object> payload = new HashMap<>();
		payload.put("rsn", rsn);
		payload.put("timestamp", System.currentTimeMillis());
		payload.put("checksum", checksum);
		payload.put("bankValue", bankValue);
		
		// Include delta information
		String syncType = (String) delta.get("type");
		payload.put("syncType", syncType);
		
		if ("full".equals(syncType))
		{
			payload.put("data", delta.get("data"));
			log.info("Submitting full bank sync to: {} (item count: {}, value: {} gp)", syncServerUrl, bankData.size(), bankValue);
		}
		else
		{
			Map<String, Object> changes = new HashMap<>();
			changes.put("added", delta.get("added"));
			changes.put("updated", delta.get("updated"));
			changes.put("removed", delta.get("removed"));
			payload.put("changes", changes);
			
			int changedItems = ((Map<?, ?>) delta.get("added")).size() + 
			                   ((Map<?, ?>) delta.get("updated")).size() + 
			                   ((Map<?, ?>) delta.get("removed")).size();
			log.info("Submitting delta bank sync to: {} (changed items: {}, total value: {} gp)", syncServerUrl, changedItems, bankValue);
		}

		String jsonPayload = gson.toJson(payload);
		log.debug("Bank data payload for {}: {}", rsn, jsonPayload);

		Request request = new Request.Builder()
			.url(syncServerUrl)
			.post(RequestBody.create(JSON, jsonPayload))
			.header("Authorization", "Bearer " + jwtToken)
			.header("Content-Type", "application/json")
			.build();

		// Store reference to current bank data for success callback
		final Map<String, Integer> currentBankData = new HashMap<>(bankData);

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to submit bank data", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						String responseBody = response.body() != null ? response.body().string() : "";
						log.error("Bank data submission failed: {} - {}", response.code(), responseBody);
						
						// If unauthorized, clear the cached token
						if (response.code() == 401)
						{
							cachedJwtToken = null;
							tokenExpiryTime = 0;
						}
					}
					else
					{
						log.info("Bank data submitted successfully");
						
						// Update last synced state on success
						lastSyncedBankState = new HashMap<>(currentBankData);
						log.debug("Updated last synced bank state with {} items", lastSyncedBankState.size());
					}
				}
			}
		});
	}




	private void submitGETrade(GrandExchangeOffer offer)
	{
		String syncServerUrl = config.syncServerUrl();
		if (syncServerUrl == null || syncServerUrl.isEmpty())
		{
			log.warn("Sync server URL not configured");
			return;
		}

		// Get the player's RSN
		String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (rsn == null || rsn.isEmpty())
		{
			log.warn("Unable to determine player RSN, skipping GE trade sync");
			return;
		}

		// Determine trade type
		String tradeType = offer.getState() == GrandExchangeOfferState.BOUGHT ? "GE_BUY" : "GE_SELL";
		
		// Build items given and received
		List<Map<String, Object>> itemsGiven = new ArrayList<>();
		List<Map<String, Object>> itemsReceived = new ArrayList<>();
		
		// Calculate price per item
		long pricePerItem = offer.getSpent() / Math.max(1, offer.getQuantitySold());

		if (offer.getState() == GrandExchangeOfferState.BOUGHT)
		{
			// Buying: gave coins, received item
			Map<String, Object> coins = new HashMap<>();
			coins.put("id", 995); // Coins item ID
			coins.put("quantity", offer.getSpent());
			itemsGiven.add(coins);
			
			Map<String, Object> item = new HashMap<>();
			item.put("id", offer.getItemId());
			item.put("quantity", offer.getQuantitySold());
			item.put("pricePerItem", pricePerItem);
			itemsReceived.add(item);
		}
		else
		{
			// Selling: gave item, received coins
			Map<String, Object> item = new HashMap<>();
			item.put("id", offer.getItemId());
			item.put("quantity", offer.getQuantitySold());
			item.put("pricePerItem", pricePerItem);
			itemsGiven.add(item);
			
			Map<String, Object> coins = new HashMap<>();
			coins.put("id", 995); // Coins item ID
			coins.put("quantity", offer.getSpent());
			itemsReceived.add(coins);
		}			

		

		
		// Build payload
		Map<String, Object> payload = new HashMap<>();
		payload.put("rsn", rsn);
		payload.put("tradeType", tradeType);
		payload.put("value", offer.getSpent()); // Total GP value
		
		// Format timestamp as ISO 8601 string
		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		payload.put("timestamp", isoFormat.format(new java.util.Date()));
		
		payload.put("itemsGiven", itemsGiven);
		payload.put("itemsReceived", itemsReceived);
		
		String jsonPayload = gson.toJson(payload);
		log.debug("GE trade payload: {}", jsonPayload);
		
		// Submit to /sync/trades endpoint
		String tradesUrl = syncServerUrl.replace("/sync/bank", "/sync/trades");
		
		Request request = new Request.Builder()
			.url(tradesUrl)
			.post(RequestBody.create(JSON, jsonPayload))
			.header("Authorization", "Bearer " + cachedJwtToken)
			.header("Content-Type", "application/json")
			.build();
		
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to submit GE trade", e);
			}
			
			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						String errorBody = response.body() != null ? response.body().string() : "No error body";
						log.error("GE trade submission failed: {} - {}", response.code(), errorBody);
					}
					else
					{
						log.info("GE trade submitted successfully");
					}
				}
			}
		});
	}


	@Provides
	LootbagConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootbagConfig.class);
	}

	// Helper inner class to snapshot GE data
	private static class SnapshotGEOffer implements GrandExchangeOffer
	{
		private final GrandExchangeOfferState state;
		private final int itemId;
		private final int quantitySold;
		private final int spent;
		private final int totalQuantity;
		private final int price;

		public SnapshotGEOffer(GrandExchangeOfferState state, int itemId, int quantitySold, int spent, int totalQuantity, int price)
		{
			this.state = state;
			this.itemId = itemId;
			this.quantitySold = quantitySold;
			this.spent = spent;
			this.totalQuantity = totalQuantity;
			this.price = price;
		}

		@Override public GrandExchangeOfferState getState() { return state; }
		@Override public int getItemId() { return itemId; }
		@Override public int getQuantitySold() { return quantitySold; }
		@Override public int getSpent() { return spent; }
		@Override public int getTotalQuantity() { return totalQuantity; }
		@Override public int getPrice() { return price; }
	}
}
