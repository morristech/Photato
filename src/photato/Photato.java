package photato;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.file.FileSystem;
import photato.core.PhotatoFilesManager;
import photato.core.metadata.MetadataAggregator;
import photato.core.resize.thumbnails.ThumbnailGenerator;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import photato.core.resize.fullscreen.FullScreenImageGetter;
import photato.core.metadata.gps.IGpsCoordinatesDescriptionGetter;
import photato.controllers.CssHandler;
import photato.controllers.DefaultHandler;
import photato.controllers.FolderListHandler;
import photato.controllers.ImageHandler;
import photato.controllers.JsHandler;
import photato.core.metadata.exif.ExifToolDownloader;
import photato.core.metadata.gps.OSMGpsCoordinatesDescriptionGetter;
import java.nio.file.Path;
import java.util.Enumeration;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import photato.controllers.LoadingHandler;
import photato.controllers.VideoHandler;
import photato.core.resize.ffmpeg.FfmpegDownloader;

public class Photato {

    public static final String[] supportedPictureExtensions = new String[]{"jpg", "jpeg", "png", "bmp"};
    public static final String[] supportedVideoExtensions = new String[]{"mp4", "webm"};
    private static final String serverName = "Photato";
    private static final String thumbnailCacheFolder = "cache/thumbnails";
    private static final String fullscreenCacheFolder = "cache/fullscreen";
    private static final String extractedPicturesCacheFolder = "cache/extracted";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: <picturesRootFolder>");
            System.exit(-1);
        }

        FileSystem fileSystem = FileSystems.getDefault();
        Path rootFolder = getRootFolder(fileSystem, args[0]);

        HttpServer server = getDefaultServer(fileSystem.getPath("www"));
        server.start();

        System.out.println("Starting exploration of folder " + rootFolder + "...");

        if (!Files.exists(fileSystem.getPath("cache"))) {
            Files.createDirectory(fileSystem.getPath("cache"));
        }

        HttpClient httpClient = HttpClientBuilder.create().setUserAgent(serverName).build();

        ExifToolDownloader.run(httpClient, fileSystem, PhotatoConfig.forceFfmpegToolsDownload);
        FfmpegDownloader.run(httpClient, fileSystem, PhotatoConfig.forceExifToolsDownload);

        ThumbnailGenerator thumbnailGenerator = new ThumbnailGenerator(fileSystem, rootFolder, thumbnailCacheFolder, extractedPicturesCacheFolder, PhotatoConfig.thumbnailHeight, PhotatoConfig.thumbnailQuality);
        IGpsCoordinatesDescriptionGetter gpsCoordinatesDescriptionGetter = new OSMGpsCoordinatesDescriptionGetter(httpClient, PhotatoConfig.addressElementsCount);
        MetadataAggregator metadataGetter = new MetadataAggregator(fileSystem, "cache/metadata.cache", gpsCoordinatesDescriptionGetter);
        FullScreenImageGetter fullScreenImageGetter = new FullScreenImageGetter(fileSystem, rootFolder, fullscreenCacheFolder, extractedPicturesCacheFolder, PhotatoConfig.fullScreenPictureQuality, PhotatoConfig.maxFullScreenPictureWitdh, PhotatoConfig.maxFullScreenPictureHeight);

        PhotatoFilesManager photatoFilesManager = new PhotatoFilesManager(rootFolder, fileSystem, metadataGetter, thumbnailGenerator, fullScreenImageGetter, PhotatoConfig.prefixModeOnly, PhotatoConfig.indexFolderName, PhotatoConfig.useParallelPicturesGeneration);

        // Closing tmp server
        server.shutdown(5, TimeUnit.SECONDS);

        server = ServerBootstrap.bootstrap()
                .setListenerPort(PhotatoConfig.serverPort)
                .setServerInfo(serverName)
                .setSocketConfig(getSocketConfig())
                .setExceptionLogger(new StdErrorExceptionLogger())
                .registerHandler(Routes.rawVideosRootUrl + "/*", new VideoHandler(rootFolder, Routes.rawVideosRootUrl))
                .registerHandler(Routes.rawPicturesRootUrl + "/*", new ImageHandler(rootFolder, Routes.rawPicturesRootUrl))
                .registerHandler(Routes.fullScreenPicturesRootUrl + "/*", new ImageHandler(fileSystem.getPath(fullscreenCacheFolder), Routes.fullScreenPicturesRootUrl))
                .registerHandler(Routes.thumbnailRootUrl + "/*", new ImageHandler(fileSystem.getPath(thumbnailCacheFolder), Routes.thumbnailRootUrl))
                .registerHandler(Routes.listItemsApiUrl, new FolderListHandler(Routes.listItemsApiUrl, photatoFilesManager))
                .registerHandler("/img/*", new ImageHandler(fileSystem.getPath("www/img"), "/img"))
                .registerHandler("/js/*", new JsHandler(fileSystem.getPath("www/js"), "/js"))
                .registerHandler("/css/*", new CssHandler(fileSystem.getPath("www/css"), "/css"))
                .registerHandler("*", new DefaultHandler(fileSystem.getPath("www")))
                .create();
        server.start();
        System.out.println("Server started on port " + server.getLocalPort() + " (http://" + getLocalIp() + ":" + server.getLocalPort() + ")");
        server.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    private static Path getRootFolder(FileSystem fileSystem, String args0) {
        if (!args0.endsWith("/")) {
            args0 += "/";
        }
        return fileSystem.getPath(args0);
    }

    private static String getLocalIp() {
        try {
            InetAddress inet = InetAddress.getLocalHost();
            InetAddress[] ips = InetAddress.getAllByName(inet.getCanonicalHostName());
            for (InetAddress ip : ips) {
                String ipStr = ip.getHostAddress();
                if (ipStr.startsWith("192.168.")) {
                    return ipStr;
                }
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static HttpServer getDefaultServer(Path folderRoot) {
        return ServerBootstrap.bootstrap()
                .setListenerPort(PhotatoConfig.serverPort)
                .setServerInfo(serverName)
                .setSocketConfig(getSocketConfig())
                .setExceptionLogger(new StdErrorExceptionLogger())
                .registerHandler("*", new LoadingHandler(folderRoot))
                .create();
    }

    private static SocketConfig getSocketConfig() {
        return SocketConfig.custom()
                .setSoTimeout(60000)
                .setTcpNoDelay(true)
                .build();
    }

}
