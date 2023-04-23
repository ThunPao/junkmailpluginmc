package me.mpz.junkmail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

public class MySQLDatabase {
    private Connection connection;
    private final Plugin plugin;

    public MySQLDatabase (Plugin plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;

    }

    public boolean connect() {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("mysql.host");
        int port = config.getInt("mysql.port");
        String database = config.getString("mysql.database");
        String username = config.getString("mysql.username");
        String password = config.getString("mysql.password");
        boolean useSSL = config.getBoolean("mysql.ssl");

        try {
            Class.forName("com.mysql.jdbc.Driver");
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSSL,
                    username, password);
            return true;
        } catch (SQLException | ClassNotFoundException ex) {
            plugin.getLogger().log(Level.SEVERE, "Error connecting to MySQL database", ex);
            return false;
        }
    }

    public void disconnect() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Error disconnecting from MySQL database", ex);
        }
    }

    public boolean executeUpdate(String sql, Object... args) {
        try {
            PreparedStatement stmt = connection.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                stmt.setObject(i + 1, args[i]);
            }
            stmt.executeUpdate();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Error executing update statement", ex);
            return false;
        }
    }

    public ResultSet executeQuery(String sql, Object... args) {
        try {
            PreparedStatement stmt = connection.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                stmt.setObject(i + 1, args[i]);
            }
            return stmt.executeQuery();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Error executing query statement", ex);
            return null;
        }
    }
}
