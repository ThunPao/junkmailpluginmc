package me.mpz.junkmail;




import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;


import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin implements CommandExecutor, Listener {
    private MySQLDatabase database;
    private Map<String, List<String>> commandContainer = new HashMap<>();
    private ItemStack displayItem = new ItemStack(Material.PAPER);

    private Connection connection;



    private static int SLOTS_PER_PAGE = 21;

    @Override
    public void onEnable() {
        // Load the database configuration from config.yml
        // Generate the config file if it doesn't exist
        generateConfig();
        // Register events
        Bukkit.getPluginManager().registerEvents(this, this);
        // Load the database configuration from config.yml
        File configFile = new File(getDataFolder(), "config.yml");

        // Check if config file exists
        if (!configFile.exists()) {
            // Create the default config file
            saveDefaultConfig();
        }

        YamlConfiguration config = new YamlConfiguration();

        // Load the config from the file
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Failed to load config.yml: " + e.getMessage());

            setEnabled(false);
            return;
        }

        // Check if all required config options are present
        if (!config.contains("mysql.host") || !config.contains("mysql.port") || !config.contains("mysql.database")
                || !config.contains("mysql.username") || !config.contains("mysql.password")) {
            getLogger().severe("Invalid config.yml. Please make sure all required options are present.");
            setEnabled(false);
            return;
        }

        // Connect to the MySQL database
        Connection connection = null;
        try {
            connection = getConnection();
            database = new MySQLDatabase(this, connection);
        } catch (SQLException e) {
            getLogger().severe("Failed to connect to MySQL database: " + e.getMessage());
            setEnabled(false);
            return;
        }

        // Create the mailbox table if it doesn't exist
        if (!database.executeUpdate("CREATE TABLE IF NOT EXISTS mailbox (id INT(11) NOT NULL AUTO_INCREMENT, player_name VARCHAR(32) NOT NULL, command TEXT NOT NULL, PRIMARY KEY (id))")) {
            getLogger().severe("Failed to create mailbox table.");
            setEnabled(false);
            return;
        }

        // Load mailbox data from the MySQL database
        try (ResultSet resultSet = database.executeQuery("SELECT player_name, command FROM mailbox")) {
            while (resultSet.next()) {
                String playerName = resultSet.getString("player_name");
                String command = resultSet.getString("command");

                List<String> playerCommands = commandContainer.getOrDefault(playerName, new ArrayList<>());
                playerCommands.add(command);
                commandContainer.put(playerName, playerCommands);
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to load mailbox data from MySQL database.");
            e.printStackTrace();
            setEnabled(false);
            return;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // Ignore error when closing the connection
                }
            }
        }
    }

    private Connection getConnection() throws SQLException {
        // Load the MySQL database settings from the configuration file
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        String host = config.getString("mysql.host");
        if (host == null) {
            getLogger().severe("Missing 'mysql.host' property in config.yml.");
            setEnabled(false);
            return null;
        }

        int port = config.getInt("mysql.port", 3306);
        String databaseName = config.getString("mysql.database", "owlbox_db");
        String username = config.getString("mysql.username", "root");
        String password = config.getString("mysql.password", "");
        boolean useSSL = config.getBoolean("mysql.ssl", false);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?useSSL=" + useSSL;

        // Create a connection to the MySQL database
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("autoReconnect", "true");
        properties.setProperty("maxReconnects", "3");
        Connection conn = DriverManager.getConnection(url, properties);

        return conn;
    }




    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("This command can only be executed by a player or the console.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player) {
                // Show the first page of the mailbox inventory to the player
                showMailboxInventory((Player) sender, 1);
            } else {
                sender.sendMessage("Only players can use this command without any arguments.");
            }
        } else if (args[0].equalsIgnoreCase("store") && args.length >= 3) {
            // Store a command in the container
            String playerName = args[1];
            String commandString = String.join(" ", args).substring(args[0].length() + args[1].length() + 2);

            // Replace %player% with the player's name
            commandString = commandString.replace("%player%", playerName);

            // Add the command to the container
            List<String> playerCommands = commandContainer.getOrDefault(playerName, new ArrayList<>());
            playerCommands.add(commandString);
            commandContainer.put(playerName, playerCommands);

            // Store the command in the database
            try {
                connection = getConnection();
                database = new MySQLDatabase(this, connection);

                database.executeUpdate("INSERT INTO mailbox (player_name, command) VALUES (?, ?)", playerName, commandString);
            } catch (SQLException e) {
                sender.sendMessage("Failed to store command in database.");
                return true;
            }

            sender.sendMessage("Command stored in mailbox.");

        } else if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            // Reload the plugin configuration from disk
            reloadConfig();
//            displayItem = new ItemStack(Material.PAPER);


            // displayItem = getConfig().getItemStack("display-item", new ItemStack(Material.PAPER));

            SLOTS_PER_PAGE = getConfig().getInt("slots-per-page", 9);
            sender.sendMessage("Mailbox configuration reloaded.");
        } else {
            sender.sendMessage("Invalid command usage. Usage: /mailbox [store <player> <command>] | [reload]");
        }

        return true;
    }

    private void showMailboxInventory(Player player, int page) {
        String playerName = player.getName();
        int numCommands = 0;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM mailbox WHERE player_name = ?")) {

            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                numCommands = rs.getInt(1);
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to get command count for player " + playerName + ": " + e.getMessage());
            player.sendMessage("An error occurred while loading your mailbox.");
            return;
        }

        // Calculate the number of slots needed for the inventory
        int numCommandsPerPage = SLOTS_PER_PAGE - 1; // Subtract one for navigation buttons
        int numPages = (int) Math.ceil((double) numCommands / numCommandsPerPage);
        int numSlots = Math.min(numPages * SLOTS_PER_PAGE, 54);
        numSlots = Math.max(numSlots, 9);
        numSlots = (numSlots / 9) * 9; // Round down to nearest multiple of 9

        Inventory mailboxInventory = Bukkit.createInventory(null, numSlots, getConfig().getString("mailbox-title", "§0Mailbox") + " (Page " + page + ")");

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT command, COUNT(*) FROM mailbox WHERE player_name = ? GROUP BY command ORDER BY id LIMIT ?, ?")) {

            stmt.setString(1, playerName);
            stmt.setInt(2, (page - 1) * numCommandsPerPage);
            stmt.setInt(3, numCommandsPerPage);

            ResultSet rs = stmt.executeQuery();

            // Add stored commands to the inventory
            while (rs.next()) {
                String command = rs.getString(1);
                int count = rs.getInt(2);
                ItemStack commandItem = new ItemStack(displayItem.getType());
                ItemMeta meta = commandItem.getItemMeta();
                meta.setDisplayName(command);
                commandItem.setItemMeta(meta);
                commandItem.setAmount(count); // set amount to number of duplicates
                mailboxInventory.addItem(commandItem);
            }

        } catch (SQLException e) {
            getLogger().severe("Failed to load commands for player " + playerName + ": " + e.getMessage());
            player.sendMessage("An error occurred while loading your mailbox.");
            return;
        }

        // Add navigation buttons to switch between pages
        ItemStack prevPageItem = new ItemStack(Material.ARROW);
        ItemMeta prevPageMeta = prevPageItem.getItemMeta();
        prevPageMeta.setDisplayName("Previous Page");
        prevPageItem.setItemMeta(prevPageMeta);

        ItemStack nextPageItem = new ItemStack(Material.ARROW);
        ItemMeta nextPageMeta = nextPageItem.getItemMeta();
        nextPageMeta.setDisplayName("Next Page");
        nextPageItem.setItemMeta(nextPageMeta);

        if (page > 1) {
            mailboxInventory.setItem(numSlots - 9, prevPageItem);
        }
        if (numCommands > (page * SLOTS_PER_PAGE)) {
            mailboxInventory.setItem(numSlots - 1, nextPageItem);
        }

        // Open the inventory for the player
        player.openInventory(mailboxInventory);
    }





    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Check if the clicked inventory is the mailbox
        String mailboxTitle = getConfig().getString("mailbox-title", "§0Mailbox");
        if (event.getView().getTitle().startsWith(mailboxTitle)) {
            event.setCancelled(true);

            String[] titleParts = event.getView().getTitle().split(" ");
            int page = Integer.parseInt(titleParts[titleParts.length - 1].replace(")", ""));

            // Check if the clicked item is a stored command
            if (event.getCurrentItem() != null && event.getCurrentItem().hasItemMeta()) {
                String displayName = event.getCurrentItem().getItemMeta().getDisplayName();
                for (String playerName : commandContainer.keySet()) {
                    List<String> playerCommands = commandContainer.get(playerName);
                    if (playerCommands.contains(displayName)) {
                        // Execute the stored command as the console
                        String commandToExecute = displayName;
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute);

                        // Remove one of the clicked item from the mailbox inventory
                        ItemStack clickedItem = event.getCurrentItem();
                        if (clickedItem.getAmount() == 1) {
                            event.setCurrentItem(null);
                        } else {
                            clickedItem.setAmount(clickedItem.getAmount() - 1);
                        }

                        // Remove the command from the database
                        try (Connection conn = getConnection();
                             PreparedStatement stmt = conn.prepareStatement("DELETE FROM mailbox WHERE player_name = ? AND command = ? LIMIT 1")) {

                            stmt.setString(1, playerName);
                            stmt.setString(2, displayName);
                            stmt.executeUpdate();
                        } catch (SQLException e) {
                            getLogger().severe("Failed to remove command " + displayName + " from mailbox for player " + playerName + ": " + e.getMessage());
                            return;
                        }

                        // Remove the command from the container
                        playerCommands.remove(displayName);
                        commandContainer.put(playerName, playerCommands);

                        // Refresh the mailbox inventory
                        showMailboxInventory((Player) event.getWhoClicked(), page);
                        return;
                    }
                }
            }

            // Check if the clicked item is a pagination button
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
                int numCommands = commandContainer.getOrDefault(event.getWhoClicked().getName(), new ArrayList<>()).size();
                int numPages = (int) Math.ceil((double) numCommands / SLOTS_PER_PAGE);

                if (event.getCurrentItem().getItemMeta().getDisplayName().equals("Previous Page")) {
                    // Show the previous page of the mailbox inventory
                    if (page > 1) {
                        showMailboxInventory((Player) event.getWhoClicked(), page - 1);
                    }
                } else if (event.getCurrentItem().getItemMeta().getDisplayName().equals("Next Page")) {
                    // Show the next page of the mailbox inventory
                    if (page < numPages) {
                        showMailboxInventory((Player) event.getWhoClicked(), page + 1);
                    }
                }
            }
        }
    }



    private void saveMailboxData() {
        if (connection == null) {
            getLogger().severe("Connection is null, cannot save mailbox data.");
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("TRUNCATE TABLE mailbox")) {
            statement.executeUpdate();

            for (Map.Entry<String, List<String>> entry : commandContainer.entrySet()) {
                String playerName = entry.getKey();
                List<String> commands = entry.getValue();

                for (String command : commands) {
                    try (PreparedStatement insertStatement = connection.prepareStatement(
                            "INSERT INTO mailbox (player_name, command) VALUES (?, ?)")) {
                        insertStatement.setString(1, playerName);
                        insertStatement.setString(2, command);
                        insertStatement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to save mailbox data to MySQL database.");
            e.printStackTrace();
        }
    }

    public void generateConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            // Create the default config file
            saveDefaultConfig();
            getLogger().info("config.yml created successfully.");
        } else {
            getLogger().info("config.yml already exists, skipping creation.");
        }
    }


    @Override
    public void onDisable() {
        // Save the display item to config
        getConfig().set("display-item", displayItem);
        saveConfig();

        // Save the mailbox data to the MySQL database
        saveMailboxData();

        // Close the MySQL database connection
        if (database != null) {
            database.disconnect();
        }
        getLogger().info("Disconnected from MySQL database.");
    }



}
