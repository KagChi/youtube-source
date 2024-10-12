package dev.lavalink.youtube.clients;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.clients.skeleton.StreamingNonMusicClient;
import org.jetbrains.annotations.NotNull;

public class Ios extends StreamingNonMusicClient {
    public static String CLIENT_VERSION = "19.32.8";

    public static ClientConfig BASE_CONFIG = new ClientConfig()
        .withApiKey("AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc")
        .withUserAgent(String.format("com.google.ios.youtube/%s (iPhone14,5; U; CPU iOS 17_6 like Mac OS X;)", CLIENT_VERSION))
        .withClientName("IOS")
        .withClientField("clientVersion", CLIENT_VERSION)
        .withClientField("osVersion", "17.6.1.21G93")
        .withClientField("osName", "iPhone")
        .withClientField("deviceModel", "iPhone14,5")
        .withClientField("deviceMake", "Apple")
        .withUserField("lockedSafetyMode", false);

    protected ClientOptions options;

    public Ios() {
        this(ClientOptions.DEFAULT);
    }

    public Ios(@NotNull ClientOptions options) {
        this.options = options;
    }

    @Override
    @NotNull
    protected ClientConfig getBaseClientConfig(@NotNull HttpInterface httpInterface) {
        return BASE_CONFIG.copy();
    }

    @Override
    @NotNull
    protected JsonBrowser extractPlaylistVideoList(@NotNull JsonBrowser json) {
        return json.get("contents")
            .get("singleColumnBrowseResultsRenderer")
            .get("tabs")
            .index(0)
            .get("tabRenderer")
            .get("content")
            .get("sectionListRenderer")
            .get("contents")
            .index(0)
            .get("itemSectionRenderer")
            .get("contents")
            .index(0)
            .get("playlistVideoListRenderer");
    }

    @Override
    @NotNull
    public String getPlayerParams() {
        return MOBILE_PLAYER_PARAMS;
    }

    @Override
    @NotNull
    public ClientOptions getOptions() {
        return this.options;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return BASE_CONFIG.getName();
    }
}
