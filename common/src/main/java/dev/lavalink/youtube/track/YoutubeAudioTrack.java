package dev.lavalink.youtube.track;

import com.sedmelluq.discord.lavaplayer.container.adts.AdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.ClientInformation;
import dev.lavalink.youtube.UrlTools;
import dev.lavalink.youtube.UrlTools.UrlInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.invi.InviClient;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.sedmelluq.discord.lavaplayer.container.Formats.MIME_AUDIO_WEBM;
import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.decodeUrlEncodedItems;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static com.sedmelluq.discord.lavaplayer.tools.Units.CONTENT_LENGTH_UNKNOWN;

/**
 * Audio track that handles processing Youtube videos as audio tracks.
 */
public class YoutubeAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(YoutubeAudioTrack.class);

  private final YoutubeAudioSourceManager sourceManager;

  /**
   * @param trackInfo Track info
   * @param sourceManager Source manager which was used to find this track
   */
  public YoutubeAudioTrack(@NotNull AudioTrackInfo trackInfo,
                           @NotNull YoutubeAudioSourceManager sourceManager) {
    super(trackInfo);
    this.sourceManager = sourceManager;
  }

  @NotNull
  protected JsonBrowser loadJsonResponse(@NotNull HttpInterface httpInterface,
                                         @NotNull HttpGet request,
                                         @NotNull String context) throws IOException {
    log.debug("Requesting {} ({})", context, request.getURI());

    try (CloseableHttpResponse response = httpInterface.execute(request)) {
      HttpClientTools.assertSuccessWithContent(response, context);
      // todo: flag for checking json content type?
      //       from my testing, json is always returned so might not be necessary.
      HttpClientTools.assertJsonContentType(response);

      String json = EntityUtils.toString(response.getEntity());
      log.trace("Response from {} ({}) {}", request.getURI(), context, json);

      return JsonBrowser.parse(json);
    }
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    if (!sourceManager.getInviClients().isEmpty()) {
      for (InviClient inviClient : sourceManager.getInviClients()) {
        HttpGet request = new HttpGet(String.format("%s/v1/videos/%s", inviClient.getApiRoute(), trackInfo.identifier));

        try {
          JsonBrowser json = loadJsonResponse(this.sourceManager.getInterface(), request, "Stream Response");

          // Check for errors in the JSON response
          if (json.get("error").text() == null) {
            List<JsonBrowser> formats = json.get("adaptiveFormats").values();

            // Find the best format based on encoding and channels
            JsonBrowser bestFormat = formats.stream()
                    .filter(format -> {
                      String encoding = format.get("encoding").text();
                      return "opus".equals(encoding) || "aac".equals(encoding);
                    })
                    .filter(format -> format.get("audioChannels").asLong(0) <= 2)
                    .max((format1, format2) -> {
                      long bitrate1 = format1.get("bitrate").asLong(0);
                      long bitrate2 = format2.get("bitrate").asLong(0);

                      // Compare by bitrate first
                      if (bitrate1 != bitrate2) {
                        return Long.compare(bitrate1, bitrate2);
                      }

                      // If bitrate is the same, compare by sample rate
                      long sampleRate1 = format1.get("audioSampleRate").asLong(0);
                      long sampleRate2 = format2.get("audioSampleRate").asLong(0);
                      return Long.compare(sampleRate1, sampleRate2);
                    })
                    .orElseThrow(() -> new FriendlyException("No suitable formats found.", FriendlyException.Severity.SUSPICIOUS, null));

            URI formatUrl = new URI(bestFormat.get("url").text());
            String encoding = bestFormat.get("encoding").text();

            if (inviClient.getProxies().isEmpty()) {
              if ("opus".equals(encoding)) {
                YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(sourceManager.getInterface(), formatUrl, CONTENT_LENGTH_UNKNOWN);
                processDelegate(new MatroskaAudioTrack(trackInfo, stream), localExecutor);
              } else if ("aac".equals(encoding)) {
                YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(sourceManager.getInterface(), formatUrl, CONTENT_LENGTH_UNKNOWN);
                processDelegate(new AdtsAudioTrack(trackInfo, stream), localExecutor);
              } else {
                  throw new FriendlyException("No suitable formats found.", Severity.SUSPICIOUS, null);
              }

              return; // Exit once successful
            }

            // Try each proxy in order
            for (String proxy : inviClient.getProxies()) {
              try {
                // Update the URI with the current proxy
                URI updatedUri = new URI(
                        formatUrl.getScheme(),                // Preserve scheme (http/https)
                        formatUrl.getUserInfo(),              // Preserve user info (if any)
                        proxy.split(":")[0],                  // New host
                        Integer.parseInt(proxy.split(":")[1]), // New port
                        formatUrl.getPath(),                  // Preserve path
                        formatUrl.getQuery(),                 // Preserve query
                        formatUrl.getFragment()                // Preserve fragment (if any)
                );

                log.debug("Trying format URL: " + updatedUri);

                if ("opus".equals(encoding)) {
                  YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(sourceManager.getInterface(), updatedUri, CONTENT_LENGTH_UNKNOWN);
                  processDelegate(new MatroskaAudioTrack(trackInfo, stream), localExecutor);
                } else if ("aac".equals(encoding)) {
                  YoutubePersistentHttpStream stream = new YoutubePersistentHttpStream(sourceManager.getInterface(), updatedUri, CONTENT_LENGTH_UNKNOWN);
                  processDelegate(new AdtsAudioTrack(trackInfo, stream), localExecutor);
                } else {
                  throw new FriendlyException("No suitable formats found.", Severity.SUSPICIOUS, null);
                }

                return; // Exit once successful
              } catch (RuntimeException e) {
                String message = e.getMessage();
                if ("Not success status code: 403".equals(message)) {
                  log.warn("Access denied: 403 on " + proxy + ". Trying next proxy...");
                } else if ("Invalid status code for Stream Response: 503".equals(message)) {
                  log.warn("Proxy down, trying next proxy...");
                } else {
                  log.error("An error occurred while processing the stream: " + message, e);
                  throw e;
                }
              } catch (Exception e) {
                log.error("An unexpected error occurred: " + e.getMessage(), e);
                throw e; // Rethrow any other exceptions
              }
            }

            // If all proxies fail, throw a final exception
            throw new RuntimeException("All proxies failed to connect.");
          } else {
            throw new RuntimeException(json.get("error").text());
          }

        } catch (IOException e) {
          throw new FriendlyException("Could not read stream response.", FriendlyException.Severity.SUSPICIOUS, e);
        }
      }
    }

    Client[] clients = sourceManager.getClients();

    if (Arrays.stream(clients).noneMatch(Client::supportsFormatLoading)) {
      throw new FriendlyException("This video cannot be played", Severity.COMMON,
          new RuntimeException("None of the registered clients supports loading of formats"));
    }

    try (HttpInterface httpInterface = sourceManager.getInterface()) {
      Exception lastException = null;

      for (Client client : clients) {
        if (!client.supportsFormatLoading()) {
          continue;
        }

        try {
          processWithClient(localExecutor, httpInterface, client, 0);
          return; // stream played through successfully, short-circuit.
        } catch (RuntimeException e) {
          // store exception so it can be thrown if we run out of clients to
          // load formats with.
          e.addSuppressed(ClientInformation.create(client));
          lastException = e;

          if (e instanceof FriendlyException) {
            // usually thrown by getPlayabilityStatus when loading formats.
            // these aren't considered fatal, so we just store them and continue.
            continue;
          }

          String message = e.getMessage();

          if ("Not success status code: 403".equals(message) ||
              "Invalid status code for player api response: 400".equals(message) ||
              (message != null && message.contains("No supported audio streams available"))) {
            continue; // try next client
          }

          throw e; // Unhandled exception, just throw.
        }
      }

      if (lastException != null) {
        if (lastException instanceof FriendlyException) {
          if (!"YouTube WebM streams are currently not supported.".equals(lastException.getMessage())) {
            // Rethrow certain FriendlyExceptions as suspicious to ensure LavaPlayer logs them.
            throw new FriendlyException(lastException.getMessage(), Severity.SUSPICIOUS, lastException.getCause());
          }

          throw lastException;
        }

        throw ExceptionTools.toRuntimeException(lastException);
      }
    } catch (CannotBeLoaded e) {
      throw ExceptionTools.wrapUnfriendlyExceptions("This video is unavailable", Severity.SUSPICIOUS, e.getCause());
    }
  }

  private void processWithClient(LocalAudioTrackExecutor localExecutor,
                                 HttpInterface httpInterface,
                                 Client client,
                                 long streamPosition) throws CannotBeLoaded, Exception {
    FormatWithUrl augmentedFormat = loadBestFormatWithUrl(httpInterface, client);
    log.debug("Starting track with URL from client {}: {}", client.getIdentifier(), augmentedFormat.signedUrl);

    try {
      if (trackInfo.isStream || augmentedFormat.format.getContentLength() == CONTENT_LENGTH_UNKNOWN) {
        processStream(localExecutor, httpInterface, augmentedFormat);
      } else {
        processStatic(localExecutor, httpInterface, augmentedFormat, streamPosition);
      }
    } catch (StreamExpiredException e) {
      processWithClient(localExecutor, httpInterface, client, e.lastStreamPosition);
    } catch (RuntimeException e) {
      if ("Not success status code: 403".equals(e.getMessage())) {
        if (localExecutor.getPosition() < 3000) {
          throw e; // bad stream URL, try the next client.
        }
      }

      // contains("No route to host") || contains("Read timed out")
      // augmentedFormat.getFallback()

      throw e;
    }
  }

  private void processStatic(LocalAudioTrackExecutor localExecutor,
                             HttpInterface httpInterface,
                             FormatWithUrl augmentedFormat,
                             long streamPosition) throws Exception {
    YoutubePersistentHttpStream stream = null;

    try {
      stream = new YoutubePersistentHttpStream(httpInterface, augmentedFormat.signedUrl, augmentedFormat.format.getContentLength());

      if (streamPosition > 0) {
        stream.seek(streamPosition);
      }

      if (augmentedFormat.format.getType().getMimeType().endsWith("/webm")) {
        processDelegate(new MatroskaAudioTrack(trackInfo, stream), localExecutor);
      } else {
        processDelegate(new MpegAudioTrack(trackInfo, stream), localExecutor);
      }
    } catch (RuntimeException e) {
      if ("Not success status code: 403".equals(e.getMessage()) && augmentedFormat.isExpired() && stream != null) {
        throw new StreamExpiredException(stream.getPosition(), e);
      }

      throw e;
    } finally {
      if (stream != null) {
        stream.close();
      }
    }
  }

  private void processStream(LocalAudioTrackExecutor localExecutor,
                             HttpInterface httpInterface,
                             FormatWithUrl augmentedFormat) throws Exception {
    if (MIME_AUDIO_WEBM.equals(augmentedFormat.format.getType().getMimeType())) {
      throw new FriendlyException("YouTube WebM streams are currently not supported.", Severity.COMMON, null);
    }

    // TODO: Catch 403 and retry? Can't use position though because it's a livestream.
    processDelegate(new YoutubeMpegStreamAudioTrack(trackInfo, httpInterface, augmentedFormat.signedUrl), localExecutor);
  }

  @NotNull
  private FormatWithUrl loadBestFormatWithUrl(@NotNull HttpInterface httpInterface,
                                              @NotNull Client client) throws CannotBeLoaded, Exception {
    if (!client.supportsFormatLoading()) {
      throw new RuntimeException(client.getIdentifier() + " does not support loading of formats!");
    }

    TrackFormats formats = client.loadFormats(sourceManager, httpInterface, getIdentifier());

    if (formats == null) {
      throw new FriendlyException("This video cannot be played", Severity.SUSPICIOUS, null);
    }

    StreamFormat format = formats.getBestFormat();

    URI resolvedUrl = sourceManager.getCipherManager()
        .resolveFormatUrl(httpInterface, formats.getPlayerScriptUrl(), format);

    resolvedUrl = client.transformPlaybackUri(format.getUrl(), resolvedUrl);

    return new FormatWithUrl(format, resolvedUrl);
  }

  @Override
  protected AudioTrack makeShallowClone() {
    return new YoutubeAudioTrack(trackInfo, sourceManager);
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return sourceManager;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  private static class FormatWithUrl {
    private final StreamFormat format;
    private final URI signedUrl;

    private FormatWithUrl(@NotNull StreamFormat format,
                          @NotNull URI signedUrl) {
      this.format = format;
      this.signedUrl = signedUrl;
    }

    public boolean isExpired() {
      UrlInfo urlInfo = UrlTools.getUrlInfo(signedUrl.toString(), true);
      String expire = urlInfo.parameters.get("expire");

      if (expire == null) {
        return false;
      }

      long expiresAbsMillis = Long.parseLong(expire) * 1000;
      return System.currentTimeMillis() >= expiresAbsMillis;
    }

    @Nullable
    public FormatWithUrl getFallback() {
      String signedString = signedUrl.toString();
      Map<String, String> urlParameters = decodeUrlEncodedItems(signedString, false);

      String mn = urlParameters.get("mn");

      if (mn == null) {
        return null;
      }

      String[] hosts = mn.split(",");

      if (hosts.length < 2) {
        log.warn("Cannot fallback, available hosts: {}", String.join(", ", hosts));
        return null;
      }

      String newUrl = signedString.replaceFirst(hosts[0], hosts[1]);

      try {
        URI uri = new URI(newUrl);
        return new FormatWithUrl(format, uri);
      } catch (URISyntaxException e) {
        return null;
      }
    }
  }

  private static class StreamExpiredException extends RuntimeException {
    private final long lastStreamPosition;

    private StreamExpiredException(long lastStreamPosition,
                                   @NotNull Exception cause) {
      super(cause);
      this.lastStreamPosition = lastStreamPosition;
    }
  }
}
