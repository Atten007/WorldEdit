/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.sk89q.bukkit.util.ClassSourceValidator;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.adapter.AdapterLoadException;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplLoader;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent;
import com.sk89q.worldedit.event.platform.PlatformReadyEvent;
import com.sk89q.worldedit.event.platform.PlatformUnreadyEvent;
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.internal.anvil.ChunkDeleter;
import com.sk89q.worldedit.internal.command.CommandUtil;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.registry.Registries;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.lifecycle.Lifecycled;
import com.sk89q.worldedit.util.lifecycle.SimpleLifecycled;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockCategory;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.FuzzyBlockState;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldedit.world.item.ItemCategory;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import io.papermc.lib.PaperLib;
import org.apache.logging.log4j.Logger;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.enginehub.piston.CommandManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.sk89q.worldedit.internal.anvil.ChunkDeleter.DELCHUNKS_FILE_NAME;

/**
 * Plugin for Bukkit.
 */
public class WorldEditPlugin extends JavaPlugin implements TabCompleter {

    // This must be before the Logger is initialized, which fails in 1.8
    private static final String FAILED_VERSION_CHECK =
        "\n**********************************************\n"
            + "** This Minecraft version (%s) is not supported by this version of WorldEdit.\n"
            + "** Please download an OLDER version of WorldEdit which does.\n"
            + "**********************************************\n";

    static {
        if (PaperLib.getMinecraftVersion() < 13) {
            throw new IllegalStateException(String.format(FAILED_VERSION_CHECK, Bukkit.getVersion()));
        }
    }

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    public static final String CUI_PLUGIN_CHANNEL = "worldedit:cui";
    private static WorldEditPlugin INSTANCE;
    private static final int BSTATS_PLUGIN_ID = 3328;

    private final SimpleLifecycled<BukkitImplAdapter> adapter =
        SimpleLifecycled.invalid();
    private BukkitServerInterface platform;
    private BukkitConfiguration config;

    @Override
    public void onLoad() {
        INSTANCE = this;

        //noinspection ResultOfMethodCallIgnored
        getDataFolder().mkdirs();

        WorldEdit worldEdit = WorldEdit.getInstance();

        // Setup platform
        platform = new BukkitServerInterface(this, getServer());
        worldEdit.getPlatformManager().register(platform);

        createDefaultConfiguration("config.yml"); // Create the default configuration file

        config = new BukkitConfiguration(new YAMLProcessor(new File(getDataFolder(), "config.yml"), true), this);

        Path delChunks = Paths.get(getDataFolder().getPath(), DELCHUNKS_FILE_NAME);
        if (Files.exists(delChunks)) {
            ChunkDeleter.runFromFile(delChunks, true);
        }
    }

    /**
     * Called on plugin enable.
     */
    @Override
    public void onEnable() {

        // Catch bad things being done by naughty plugins that include
        // WorldEdit's classes
        ClassSourceValidator verifier = new ClassSourceValidator(this);
        verifier.reportMismatches(ImmutableList.of(World.class, CommandManager.class, EditSession.class, Actor.class));

        WorldEdit.getInstance().getEventBus().post(new PlatformsRegisteredEvent());

        PermissionsResolverManager.initialize(this); // Setup permission resolver

        // Register CUI
        getServer().getMessenger().registerIncomingPluginChannel(this, CUI_PLUGIN_CHANNEL, new CUIChannelListener(this));
        getServer().getMessenger().registerOutgoingPluginChannel(this, CUI_PLUGIN_CHANNEL);

        // Now we can register events
        getServer().getPluginManager().registerEvents(new WorldEditListener(this), this);
        // register async tab complete, if available
        if (PaperLib.isPaper()) {
            getServer().getPluginManager().registerEvents(new AsyncTabCompleteListener(), this);
        }

        if (Bukkit.getWorlds().isEmpty()) {
            setupPreWorldData();
            // register this so we can load world-dependent data right as the first world is loading
            getServer().getPluginManager().registerEvents(new WorldInitListener(), this);
        } else {
            getLogger().warning("Server reload detected. This may cause various issues with WorldEdit and dependent plugins.");
            try {
                setupPreWorldData();
                // since worlds are loaded already, we can do this now
                setupWorldData();
            } catch (Throwable ignored) {
            }
        }

        // Enable metrics
        new Metrics(this, BSTATS_PLUGIN_ID);
        PaperLib.suggestPaper(this);
    }

    private void setupPreWorldData() {
        loadAdapter();
        initializeRegistries(); // this creates the objects matching Bukkit's enums - but doesn't fill them with data yet
        config.load();
        WorldEdit.getInstance().loadMappings();
    }

    private void setupWorldData() {
        // datapacks aren't loaded until just before the world is, and bukkit has no event for this
        // so the earliest we can do this is in WorldInit
        setupTags();

        WorldEdit.getInstance().getEventBus().post(new PlatformReadyEvent(platform));
    }

    @SuppressWarnings({ "unchecked", "deprecation", "removal" })
    private void initializeRegistries() {
        // Biome
        Registry.BIOME.forEach(biome -> {
            // TODO Fix this check for 1.22 / when we drop < 1.21 support
            if (!biome.name().equals("CUSTOM")) {
                String key = biome.getKey().toString();
                BiomeType.REGISTRY.register(key, new BiomeType(key));
            }
        });
        // Block & Item
        Registry.MATERIAL.forEach(material -> {
            String key = material.getKey().toString();
            if (material.isBlock()) {
                BlockType.REGISTRY.register(key, new BlockType(key, blockState -> {
                    // TODO Use something way less hacky than this.
                    ParserContext context = new ParserContext();
                    context.setPreferringWildcard(true);
                    context.setTryLegacy(false);
                    context.setRestricted(false);
                    try {
                        FuzzyBlockState state = (FuzzyBlockState) WorldEdit.getInstance().getBlockFactory().parseFromInput(
                                BukkitAdapter.adapt(blockState.getBlockType()).createBlockData().getAsString(), context
                        ).toImmutableState();
                        BlockState defaultState = blockState.getBlockType().getAllStates().get(0);
                        for (Map.Entry<Property<?>, Object> propertyObjectEntry : state.getStates().entrySet()) {
                            //noinspection unchecked
                            defaultState = defaultState.with((Property<Object>) propertyObjectEntry.getKey(), propertyObjectEntry.getValue());
                        }
                        return defaultState;
                    } catch (InputParseException e) {
                        getLogger().log(Level.WARNING, "Error loading block state for " + key, e);
                        return blockState;
                    }
                }));
            }
            if (material.isItem()) {
                ItemType.REGISTRY.register(key, new ItemType(key));
            }
        });
        // Entity
        Registry.ENTITY_TYPE.forEach(entityType -> {
            String key = entityType.getKey().toString();
            EntityType.REGISTRY.register(key, new EntityType(key));
        });

        // Registries only available via NMS
        BukkitImplAdapter adapter = getBukkitImplAdapter();
        if (adapter != null) {
            adapter.initializeRegistries();
        }

        // ... :|
        GameModes.get("");
        WeatherTypes.get("");
        Registries.get("");
    }

    private void setupTags() {
        // Tags
        for (Tag<Material> blockTag : Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class)) {
            String key = blockTag.getKey().toString();
            BlockCategory.REGISTRY.register(key, new BlockCategory(key));
        }
        for (Tag<Material> itemTag : Bukkit.getTags(Tag.REGISTRY_ITEMS, Material.class)) {
            String key = itemTag.getKey().toString();
            ItemCategory.REGISTRY.register(key, new ItemCategory(key));
        }
    }

    private void loadAdapter() {
        WorldEdit worldEdit = WorldEdit.getInstance();

        // Attempt to load a Bukkit adapter
        BukkitImplLoader adapterLoader = new BukkitImplLoader();

        try {
            adapterLoader.addFromPath(getClass().getClassLoader());
        } catch (IOException e) {
            LOGGER.warn("Failed to search path for Bukkit adapters");
        }

        try {
            adapterLoader.addFromJar(getFile());
        } catch (IOException e) {
            LOGGER.warn("Failed to search " + getFile() + " for Bukkit adapters", e);
        }
        try {
            BukkitImplAdapter bukkitAdapter = adapterLoader.loadAdapter();
            LOGGER.info("Using " + bukkitAdapter.getClass().getCanonicalName() + " as the Bukkit adapter");
            this.adapter.newValue(bukkitAdapter);
        } catch (AdapterLoadException e) {
            Platform platform = worldEdit.getPlatformManager().queryCapability(Capability.WORLD_EDITING);
            if (platform instanceof BukkitServerInterface) {
                LOGGER.warn(e.getMessage());
                try {
                    Class.forName("org.spigotmc.SpigotConfig");
                } catch (ClassNotFoundException e1) {
                    LOGGER.warn("CraftBukkit is not a supported Bukkit platform. Please use a supported platform, such as Paper.");
                }
            } else {
                LOGGER.info("WorldEdit could not find a Bukkit adapter for this MC version, "
                    + "but it seems that you have another implementation of WorldEdit installed (" + platform.getPlatformName() + ") "
                    + "that handles the world editing.");
            }
            this.adapter.invalidate();
        }
    }

    /**
     * Called on plugin disable.
     */
    @Override
    public void onDisable() {
        WorldEdit worldEdit = WorldEdit.getInstance();
        worldEdit.getSessionManager().unload();
        if (platform != null) {
            worldEdit.getEventBus().post(new PlatformUnreadyEvent(platform));
            worldEdit.getPlatformManager().unregister(platform);
            platform.unregisterCommands();
        }
        if (config != null) {
            config.unload();
        }
        this.getServer().getScheduler().cancelTasks(this);
    }

    /**
     * Loads and reloads all configuration.
     */
    protected void loadConfiguration() {
        config.unload();
        config.load();
        getPermissionsResolver().load();
    }

    /**
     * Create a default configuration file from the .jar.
     *
     * @param name the filename
     */
    protected void createDefaultConfiguration(String name) {
        File actual = new File(getDataFolder(), name);
        if (!actual.exists()) {
            try (InputStream stream = getResource("defaults/" + name)) {
                if (stream == null) {
                    throw new FileNotFoundException();
                }
                copyDefaultConfig(stream, actual, name);
            } catch (IOException e) {
                getLogger().severe("Unable to read default configuration: " + name);
            }
        }
    }

    private void copyDefaultConfig(InputStream input, File actual, String name) {
        try (FileOutputStream output = new FileOutputStream(actual)) {
            byte[] buf = new byte[8192];
            int length;
            while ((length = input.read(buf)) > 0) {
                output.write(buf, 0, length);
            }

            getLogger().info("Default configuration file written: " + name);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to write default config file", e);
        }
    }

    private String rebuildArguments(String commandLabel, String[] args) {
        int plSep = commandLabel.indexOf(':');
        if (plSep >= 0 && plSep < commandLabel.length() + 1) {
            commandLabel = commandLabel.substring(plSep + 1);
        }

        StringBuilder sb = new StringBuilder("/").append(commandLabel);
        if (args.length > 0) {
            sb.append(" ");
        }
        return Joiner.on(" ").appendTo(sb, args).toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        String arguments = rebuildArguments(commandLabel, args);
        CommandEvent event = new CommandEvent(wrapCommandSender(sender), arguments);
        getWorldEdit().getEventBus().post(event);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        String arguments = rebuildArguments(commandLabel, args);
        CommandSuggestionEvent event = new CommandSuggestionEvent(wrapCommandSender(sender), arguments);
        getWorldEdit().getEventBus().post(event);
        return CommandUtil.fixSuggestions(arguments, event.getSuggestions());
    }

    /**
     * Gets the session for the player.
     *
     * @param player a player
     * @return a session
     */
    public LocalSession getSession(Player player) {
        return WorldEdit.getInstance().getSessionManager().get(wrapPlayer(player));
    }

    /**
     * Gets the session for the player.
     *
     * @param player a player
     * @return a session
     */
    public EditSession createEditSession(Player player) {
        com.sk89q.worldedit.entity.Player wePlayer = wrapPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
        BlockBag blockBag = session.getBlockBag(wePlayer);

        EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
            .locatableActor(wePlayer)
            .maxBlocks(session.getBlockChangeLimit())
            .blockBag(blockBag)
            .build();
        editSession.enableStandardMode();

        return editSession;
    }

    /**
     * Remember an edit session.
     *
     * @param player a player
     * @param editSession an edit session
     */
    public void remember(Player player, EditSession editSession) {
        com.sk89q.worldedit.entity.Player wePlayer = wrapPlayer(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);

        session.remember(editSession);
        editSession.close();

        WorldEdit.getInstance().flushBlockBag(wePlayer, editSession);
    }

    /**
     * Returns the configuration used by WorldEdit.
     *
     * @return the configuration
     */
    public BukkitConfiguration getLocalConfiguration() {
        return config;
    }

    /**
     * Get the permissions resolver in use.
     *
     * @return the permissions resolver
     */
    public PermissionsResolverManager getPermissionsResolver() {
        return PermissionsResolverManager.getInstance();
    }

    /**
     * Used to wrap a Bukkit Player as a WorldEdit Player.
     *
     * @param player a player
     * @return a wrapped player
     */
    public BukkitPlayer wrapPlayer(Player player) {
        return new BukkitPlayer(this, player);
    }

    public Actor wrapCommandSender(CommandSender sender) {
        if (sender instanceof Player) {
            return wrapPlayer((Player) sender);
        } else if (config.commandBlockSupport && sender instanceof BlockCommandSender) {
            return new BukkitBlockCommandSender(this, (BlockCommandSender) sender);
        }

        return new BukkitCommandSender(this, sender);
    }

    BukkitServerInterface getInternalPlatform() {
        return platform;
    }

    /**
     * Get WorldEdit.
     *
     * @return an instance
     */
    public WorldEdit getWorldEdit() {
        return WorldEdit.getInstance();
    }

    /**
     * Gets the instance of this plugin.
     *
     * @return an instance of the plugin
     * @throws NullPointerException if the plugin hasn't been enabled
     */
    static WorldEditPlugin getInstance() {
        return checkNotNull(INSTANCE);
    }

    /**
     * Get the Bukkit implementation adapter.
     *
     * @return the adapter
     */
    Lifecycled<BukkitImplAdapter> getLifecycledBukkitImplAdapter() {
        return adapter;
    }

    BukkitImplAdapter getBukkitImplAdapter() {
        return adapter.value().orElse(null);
    }

    private class WorldInitListener implements Listener {
        private boolean loaded = false;

        @EventHandler(priority = EventPriority.LOWEST)
        public void onWorldInit(WorldInitEvent event) {
            if (loaded) {
                return;
            }
            loaded = true;
            setupWorldData();
        }
    }

    private class AsyncTabCompleteListener implements Listener {
        AsyncTabCompleteListener() {
        }

        @SuppressWarnings("UnnecessaryFullyQualifiedName")
        @EventHandler(ignoreCancelled = true)
        public void onAsyncTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event) {
            if (!event.isCommand()) {
                return;
            }

            String buffer = event.getBuffer();
            int firstSpace = buffer.indexOf(' ');
            if (firstSpace < 1) {
                return;
            }
            String label = buffer.substring(1, firstSpace);
            Plugin owner = platform.getDynamicCommands().getCommandOwner(label);
            if (owner != WorldEditPlugin.this) {
                return;
            }
            int plSep = label.indexOf(':');
            if (plSep >= 0 && plSep < label.length() + 1) {
                label = label.substring(plSep + 1);
                buffer = "/" + buffer.substring(plSep + 2);
            }
            final Optional<org.enginehub.piston.Command> command
                    = WorldEdit.getInstance().getPlatformManager().getPlatformCommandManager().getCommandManager().getCommand(label);
            if (!command.isPresent()) {
                return;
            }

            CommandSuggestionEvent suggestEvent = new CommandSuggestionEvent(wrapCommandSender(event.getSender()), buffer);
            getWorldEdit().getEventBus().post(suggestEvent);

            event.setCompletions(CommandUtil.fixSuggestions(buffer, suggestEvent.getSuggestions()));
            event.setHandled(true);
        }
    }
}
