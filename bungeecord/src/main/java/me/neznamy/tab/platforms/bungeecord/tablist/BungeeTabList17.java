package me.neznamy.tab.platforms.bungeecord.tablist;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.platforms.bungeecord.BungeeTabPlayer;
import me.neznamy.tab.shared.chat.IChatBaseComponent;
import me.neznamy.tab.shared.platform.TabList;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TabList handler for 1.7 players, which has a completely different
 * system. Entries are identified by display name, not uuids. Display
 * names are changed by removing old entry and adding a new one, which
 * requires further tracking than just the UUID.<p>
 * Because BungeeCord does not have a TabList API, we need to use packets.
 * They are sent using an internal BungeeCord method that keeps track of them,
 * so they are removed on server switch to secure parity with Velocity.
 * While BungeeCord itself does not support 1.7, some of its forks do.
 * This was tested on FlameCord fork.
 */
@RequiredArgsConstructor
public class BungeeTabList17 implements TabList {

    /** Player this TabList belongs to */
    @NotNull
    private final BungeeTabPlayer player;

    /** Because entries are identified by names and not uuids on 1.7 */
    @NotNull
    private final Map<UUID, String> userNames = new HashMap<>();

    @NotNull
    private final Map<UUID, String> displayNames = new HashMap<>();

    @Override
    public void removeEntry(@NotNull UUID entry) {
        if (!displayNames.containsKey(entry)) return; // Entry not tracked by TAB
        update(PlayerListItem.Action.REMOVE_PLAYER, createItem(null, displayNames.get(entry), 0));

        // Remove from map
        userNames.remove(entry);
        displayNames.remove(entry);
    }

    @Override
    public void updateDisplayName(@NotNull UUID entry, @Nullable IChatBaseComponent displayName) {
        if (!displayNames.containsKey(entry)) return; // Entry not tracked by TAB
        update(PlayerListItem.Action.REMOVE_PLAYER, createItem(null, displayNames.get(entry), 0));
        addEntry(new Entry.Builder(entry).displayName(displayName).name(userNames.get(entry)).build());
    }

    @Override
    public void updateLatency(@NotNull UUID entry, int latency) {
        if (!displayNames.containsKey(entry)) return; // Entry not tracked by TAB
        update(PlayerListItem.Action.UPDATE_LATENCY, createItem(null, displayNames.get(entry), latency));
    }

    @Override
    public void updateGameMode(@NotNull UUID entry, int gameMode) {/*Added in 1.8*/}

    @Override
    public void addEntry(@NotNull Entry entry) {
        String displayNameString = entry.getDisplayName() == null ? String.valueOf(entry.getName()) : entry.getDisplayName().toLegacyText();
        if (displayNameString.length() > 16) displayNameString = displayNameString.substring(0, 16); // 16 character limit
        update(PlayerListItem.Action.ADD_PLAYER, createItem(entry.getName(), displayNameString, entry.getLatency()));

        // Add to map
        userNames.put(entry.getUniqueId(), entry.getName());
        displayNames.put(entry.getUniqueId(), displayNameString);
    }

    private void update(@NotNull PlayerListItem.Action action, @NotNull PlayerListItem.Item item) {
        PlayerListItem packet = new PlayerListItem();
        packet.setAction(action);
        packet.setItems(new PlayerListItem.Item[]{item});
        ((UserConnection)player.getPlayer()).getTabListHandler().onUpdate(packet);
    }

    private PlayerListItem.Item createItem(@Nullable String username, @NotNull String displayName, int latency) {
        PlayerListItem.Item item = new PlayerListItem.Item();
        item.setUsername(username);
        item.setDisplayName(displayName);
        item.setPing(latency);
        return item;
    }

    @Override
    public void setPlayerListHeaderFooter(@NotNull IChatBaseComponent header, @NotNull IChatBaseComponent footer) {
        // Added in 1.8
    }
}
