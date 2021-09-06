package com.worldql.client;

import com.worldql.client.listeners.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;


public class WorldQLClient extends JavaPlugin {
    public static WorldQLClient pluginInstance;
    private Thread zeroMQThread;
    private ZContext context;
    private ZMQ.Socket pushSocket;
    private int zmqPortClientId;
    private PacketReader packetReader;

    @Override
    public void onEnable() {
        if (firstRun()) {
            getLogger().info("Thanks for using WorldQL & Mammoth! Please configure this plugin before enabling.");
            saveDefaultConfig();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        pluginInstance = this;

        String ip = getConfig().getString("worldql-ip");
        String worldQLPort = getConfig().getString("worldql-port");
        String worldQLEndpointPort = getConfig().getString("worldql-endpoint-port");

        getLogger().info("Initializing Mammoth WorldQL client.");
        context = new ZContext();
        pushSocket = context.createSocket(SocketType.PUSH);
        packetReader = new PacketReader();
        ZMQ.Socket handshakeSocket = context.createSocket(SocketType.REQ);
        handshakeSocket.connect("tcp://" + ip + ":" + worldQLPort);

        /*
        try (final DatagramSocket datagramSocket = new DatagramSocket()) {
            datagramSocket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            myIP = datagramSocket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            throw new RuntimeException("Couldn't determine our IP address.");
        }
         */

        Connection connection = null;
        try {
            // create a database connection
            connection = DriverManager.getConnection("jdbc:sqlite:worldql.db");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.

                statement.executeUpdate("create table if not exists chunk_sync (pk INTEGER PRIMARY KEY, x integer, y integer, last_update integer);");
            }
        } catch (SQLException e) {
            // if the error message is "out of memory",
            // it probably means no database file is found
            getLogger().severe(e.getMessage());
        }

        handshakeSocket.send(ip.getBytes(ZMQ.CHARSET), zmq.ZMQ.ZMQ_DONTWAIT);
        byte[] reply = handshakeSocket.recv(zmq.ZMQ.ZMQ_DONTWAIT);
        String assignedZeroMQPort = new String(reply, ZMQ.CHARSET);
        zmqPortClientId = Integer.parseInt(assignedZeroMQPort);

        pushSocket.connect("tcp://" + ip + ":" + worldQLEndpointPort);

        getServer().getPluginManager().registerEvents(new PlayerMoveAndLookHandler(), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinEventListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerCrouchListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractEventListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerLogOutListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerBlockPlaceListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerEditSignListener(), this);
        getServer().getPluginManager().registerEvents(new PortalCreateEventListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerLoadChunkListener(), this);
        getServer().getPluginManager().registerEvents(new OutgoingPlayerHitListener(), this);

        this.getCommand("refreshworld").setExecutor(new TestRefreshWorldCommand());

        zeroMQThread = new Thread(new ZeroMQServer(this, assignedZeroMQPort, context));
        zeroMQThread.start();
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down ZeroMQ thread.");
        context.close();
        try {
            zeroMQThread.interrupt();
            zeroMQThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    public static WorldQLClient getPluginInstance() {
        return pluginInstance;
    }

    public PacketReader getPacketReader() {
        return packetReader;
    }

    public ZMQ.Socket getPushSocket() {
        return pushSocket;
    }

    public int getZmqPortClientId() {
        return zmqPortClientId;
    }

    /**
     * Check if this is the first install of the plugin
     * @return if this is the first run of the plugin
     */
    private boolean firstRun() {
        File file = new File(getDataFolder(), "config.yml");
        return !file.exists();
    }
}
