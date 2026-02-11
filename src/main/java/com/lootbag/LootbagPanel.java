package com.lootbag;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultEditorKit;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

class LootbagPanel extends PluginPanel
{
	private final LootbagConfig config;
	private LootbagPlugin plugin;
	
	private JPanel loginPanel;
	private JPanel loggedInPanel;
	private JTextField emailField;
	private JPasswordField passwordField;
	private JButton loginButton;
	private JLabel statusLabel;
	private JLabel usernameLabel;
	private JButton logoutButton;

	@Inject
	LootbagPanel(LootbagConfig config)
	{
		super();
		this.config = config;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());

		JPanel northPanel = new JPanel(new BorderLayout());
		northPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

		JLabel title = new JLabel("Lootbag");
		title.setHorizontalAlignment(SwingConstants.CENTER);
		title.setForeground(Color.WHITE);
		northPanel.add(title, BorderLayout.CENTER);

		add(northPanel, BorderLayout.NORTH);

		buildLoginPanel();
		buildLoggedInPanel();
	}

	public void init(LootbagPlugin plugin)
	{
		this.plugin = plugin;
		updateView();
	}

	public void refresh()
	{
		SwingUtilities.invokeLater(this::updateView);
	}

	private void enableCopyPaste(JTextField field)
	{
		// Explicitly enable copy/paste actions
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), DefaultEditorKit.copyAction);
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), DefaultEditorKit.pasteAction);
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.META_DOWN_MASK), DefaultEditorKit.cutAction);
		
		// Also support Ctrl for Windows/Linux
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), DefaultEditorKit.copyAction);
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), DefaultEditorKit.pasteAction);
		field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK), DefaultEditorKit.cutAction);
	}

	private void buildLoginPanel()
	{
		loginPanel = new JPanel(new GridBagLayout());
		loginPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.insets = new Insets(5, 0, 5, 0);

		// Username label
		c.gridy = 0;
		JLabel emailLabel = new JLabel("Username:");
		emailLabel.setForeground(Color.WHITE);
		loginPanel.add(emailLabel, c);

		// Username field
		c.gridy = 1;
		emailField = new JTextField();
		emailField.setPreferredSize(new Dimension(200, 30));
		emailField.setEditable(true);
		emailField.setDragEnabled(true);
		emailField.addActionListener(e -> passwordField.requestFocusInWindow());
		enableCopyPaste(emailField);
		loginPanel.add(emailField, c);

		// Token label
		c.gridy = 2;
		JLabel passwordLabel = new JLabel("Token:");
		passwordLabel.setForeground(Color.WHITE);
		loginPanel.add(passwordLabel, c);

		// Token field
		c.gridy = 3;
		passwordField = new JPasswordField();
		passwordField.setPreferredSize(new Dimension(200, 30));
		passwordField.setEditable(true);
		passwordField.setDragEnabled(true);
		passwordField.addActionListener(e -> handleLogin());
		enableCopyPaste(passwordField);
		loginPanel.add(passwordField, c);

		// Status label
		c.gridy = 4;
		statusLabel = new JLabel("");
		statusLabel.setForeground(Color.RED);
		statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
		loginPanel.add(statusLabel, c);

		// Login button
		c.gridy = 5;
		c.insets = new Insets(10, 0, 5, 0);
		loginButton = new JButton("Sign In");
		loginButton.setPreferredSize(new Dimension(200, 30));
		loginButton.setBackground(ColorScheme.BRAND_ORANGE);
		loginButton.setForeground(Color.WHITE);
		loginButton.setFocusPainted(false);
		loginButton.addActionListener(e -> handleLogin());
		loginButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (loginButton.isEnabled())
				{
					loginButton.setBackground(ColorScheme.BRAND_ORANGE.darker());
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				loginButton.setBackground(ColorScheme.BRAND_ORANGE);
			}
		});
		loginPanel.add(loginButton, c);

		// Create account button
		c.gridy = 6;
		c.insets = new Insets(5, 0, 5, 0);
		JButton createAccountBtn = new JButton("Create Account");
		createAccountBtn.setPreferredSize(new Dimension(200, 30));
		createAccountBtn.setBackground(ColorScheme.DARK_GRAY_COLOR);
		createAccountBtn.setForeground(Color.WHITE);
		createAccountBtn.setFocusPainted(false);
		createAccountBtn.addActionListener(e -> LinkBrowser.browse(config.createAccountUrl()));
		createAccountBtn.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				createAccountBtn.setBackground(ColorScheme.DARK_GRAY_COLOR.darker());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				createAccountBtn.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		loginPanel.add(createAccountBtn, c);
	}

	private void buildLoggedInPanel()
	{
		loggedInPanel = new JPanel(new GridBagLayout());
		loggedInPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.insets = new Insets(5, 0, 5, 0);

		// Logged in as label
		c.gridy = 0;
		JLabel loggedInLabel = new JLabel("Logged in as:");
		loggedInLabel.setForeground(Color.WHITE);
		loggedInLabel.setHorizontalAlignment(SwingConstants.CENTER);
		loggedInPanel.add(loggedInLabel, c);

		// Username label
		c.gridy = 1;
		usernameLabel = new JLabel("");
		usernameLabel.setForeground(ColorScheme.BRAND_ORANGE);
		usernameLabel.setHorizontalAlignment(SwingConstants.CENTER);
		loggedInPanel.add(usernameLabel, c);

		// Logout button
		c.gridy = 2;
		c.insets = new Insets(20, 0, 5, 0);
		logoutButton = new JButton("Sign Out");
		logoutButton.setPreferredSize(new Dimension(200, 30));
		logoutButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		logoutButton.setForeground(Color.WHITE);
		logoutButton.setFocusPainted(false);
		logoutButton.addActionListener(e -> handleLogout());
		logoutButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				logoutButton.setBackground(ColorScheme.DARK_GRAY_COLOR.darker());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				logoutButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		loggedInPanel.add(logoutButton, c);
	}

	private void handleLogin()
	{
		String email = emailField.getText().trim();
		String password = new String(passwordField.getPassword());

		if (email.isEmpty() || password.isEmpty())
		{
			statusLabel.setText("Please fill in all fields");
			statusLabel.setForeground(Color.RED);
			return;
		}

		loginButton.setEnabled(false);
		loginButton.setText("Signing in...");
		statusLabel.setText("");

		plugin.login(email, password, (success, message) ->
		{
			SwingUtilities.invokeLater(() ->
			{
				loginButton.setEnabled(true);
				loginButton.setText("Sign In");

				if (success)
				{
					passwordField.setText("");
					updateView();
				}
				else
				{
					statusLabel.setText(message);
					statusLabel.setForeground(Color.RED);
				}
			});
		});
	}

	private void handleLogout()
	{
		plugin.logout();
		emailField.setText("");
		passwordField.setText("");
		statusLabel.setText("");
		updateView();
	}

	private void updateView()
	{
		removeAll();

		JPanel northPanel = new JPanel(new BorderLayout());
		northPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

		JLabel title = new JLabel("Lootbag");
		title.setHorizontalAlignment(SwingConstants.CENTER);
		title.setForeground(Color.WHITE);
		northPanel.add(title, BorderLayout.CENTER);

		add(northPanel, BorderLayout.NORTH);

		if (plugin != null && plugin.isLoggedIn())
		{
			String username = config.username();
			usernameLabel.setText(username != null && !username.isEmpty() ? username : "Unknown");
			add(loggedInPanel, BorderLayout.CENTER);
		}
		else
		{
			add(loginPanel, BorderLayout.CENTER);
		}

		revalidate();
		repaint();
	}
}
