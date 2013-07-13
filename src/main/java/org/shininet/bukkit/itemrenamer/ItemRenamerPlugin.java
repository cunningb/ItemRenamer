/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.shininet.bukkit.itemrenamer;

import java.io.File;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.milkbowl.vault.chat.Chat;

import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.shininet.bukkit.itemrenamer.configuration.ItemRenamerConfiguration;
import org.shininet.bukkit.itemrenamer.listeners.ItemRenamerPacket;
import org.shininet.bukkit.itemrenamer.listeners.ItemRenamerPlayerJoin;
import org.shininet.bukkit.itemrenamer.listeners.ItemRenamerStackRestrictor;
import org.shininet.bukkit.itemrenamer.metrics.BukkitMetrics;
import org.shininet.bukkit.itemrenamer.metrics.Updater;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

public class ItemRenamerPlugin extends JavaPlugin {
	private static boolean updateReady = false;
	private static String updateName = "";
	private static long updateSize = 0;

	public static final String updateSlug = "itemrenamer";
	
	// The current API
	private static ItemRenamerAPI renamerAPI;
	
	private Logger logger;
    private ItemRenamerConfiguration config;

    private ItemRenamerPlayerJoin listenerPlayerJoin;
	private ItemRenamerPacket listenerPacket;
    private RefreshInventoryTask refreshTask;

    // For tracking the currently selected item
    private SelectedItemTracker selectedTracker;
    
    // For restricting the current stack
    private ItemRenamerStackRestrictor stackRestrictor;
    private RenameProcessor processor;
    
    private int lastSaveCount;
	private Chat chat;
	
	/**
	 * Retrieve the renamer API.
	 * @return The renamer API.
	 */
	public static ItemRenamerAPI getRenamerAPI() {
		return renamerAPI;
	}
	
	@Override
	public void onEnable() {
		logger = getLogger();
		config = new ItemRenamerConfiguration(this, new File(getDataFolder(), "config.yml").getAbsolutePath()) {
			protected void onSynchronized() {
				lastSaveCount = getModificationCount();
				refreshTask.forceRefresh();
			}
		};
		
		if (setupChat()) {
			logger.info("Found Vault!");
		}
        processor = new RenameProcessor(config, chat);
		
		startMetrics();
		startUpdater();
		
		// Managers
		PluginManager plugins = getServer().getPluginManager();
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
		
		listenerPacket = new ItemRenamerPacket(this, processor, protocolManager, logger);
		listenerPlayerJoin = new ItemRenamerPlayerJoin(this);
		selectedTracker = new SelectedItemTracker();
		
		plugins.registerEvents(listenerPlayerJoin, this);
		plugins.registerEvents(selectedTracker.getBukkitListener(), this);
		
		// Update stack restrictor
		if (config.hasStackRestrictor()) {
			logger.info("Starting stack restrictor.");
		} else {
			logger.warning("Stack restrictor has been disabled.");
		}
		refreshStackRestrictor();			
			
        ItemRenamerCommands commandExecutor = new ItemRenamerCommands(this, config, selectedTracker);
		getCommand("ItemRenamer").setExecutor(commandExecutor);
		
		// Tasks
		refreshTask = new RefreshInventoryTask(getServer().getScheduler(), this, config);
		refreshTask.start();
		
		// Initialize the API
		renamerAPI = new ItemRenamerAPI(config, processor);
		checkWorlds();
	}
	
	private void checkWorlds() {
		Set<String> specifiedWorlds = config.getWorldKeys();
		
		// Warn if a world cannot be found
		for (String world : specifiedWorlds) {
			if (getServer().getWorld(world) == null) {
				logger.warning("Unable to find world " + world + ". Config may be invalid.");
			} else {
				// Is the pack valid
				String pack = config.getEffectiveWorldPack(world);
				
				if (config.getRenameConfig().hasPack(pack))
					logger.info("Item renaming enabled for world " + world);
				else
					logger.warning("Cannot find pack " + pack + " for world " + world);
			}
		}
		
		// "Load" default packs as well
		if (config.getDefaultPack() != null) {
			for (World world : getServer().getWorlds()) {
				if (!specifiedWorlds.contains(world.getName())) {
					logger.info("Item renaming enabled for world " + world.getName());
				}
			}
		}
	}
	
	/**
	 * Ensure that the stack restrictor is registered or not.
	 */
	public void refreshStackRestrictor() {
		if (config.hasStackRestrictor()) {
			if (stackRestrictor == null && processor != null) {
				stackRestrictor = new ItemRenamerStackRestrictor(processor);
				getServer().getPluginManager().registerEvents(stackRestrictor, this);
			}
		} else {
			if (stackRestrictor != null) {
				HandlerList.unregisterAll(stackRestrictor);
				stackRestrictor = null;
			}
		}
	}
	
	private void startUpdater() {
		try {
			if (config.isAutoUpdate() && !(updateReady)) {
				
				// Start Updater but just do a version check
				Updater updater = new Updater(this, updateSlug, this.getFile(), Updater.UpdateType.NO_DOWNLOAD, false); 
				
				// Determine if there is an update ready for us
				updateReady = updater.getResult() == Updater.UpdateResult.UPDATE_AVAILABLE; 
				updateName = updater.getLatestVersionString(); // Get the latest version
				updateSize = updater.getFileSize(); // Get latest size
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "Failed to start Updater", e);
		}
	}

	private void startMetrics() {
		try {
		    BukkitMetrics metrics = new BukkitMetrics(this);
		    metrics.start();
		} catch (Exception e) {
			logger.log(Level.WARNING, "Failed to start Metrics", e);
		}
	}

	/**
	 * Initialize reference to Vault.
	 * @return TRUE if Vault was detected and loaded, FALSE otherwise.
	 */
    private boolean setupChat() {
    	try {
	        RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(Chat.class);
	        
	        if (chatProvider != null) {
	            chat = chatProvider.getProvider();
	        }
	        return (chat != null);
    	} catch (NoClassDefFoundError e) {
    		// Nope
    		return false;
    	}
    }

	@Override
	public void onDisable() {
		// Save all changes if anything has changed
		if (config.getModificationCount() != lastSaveCount) {
			config.save();
			logger.info("Saving configuration.");
		}
		
		listenerPacket.unregister(this);
		listenerPlayerJoin.unregister();
		refreshTask.stop();
		
		// Clear API
		renamerAPI = null;
	}

	public boolean getUpdateReady() {
		return updateReady;
	}

	public String getUpdateName() {
		return updateName;
	}

	public long getUpdateSize() {
		return updateSize;
	}
}
