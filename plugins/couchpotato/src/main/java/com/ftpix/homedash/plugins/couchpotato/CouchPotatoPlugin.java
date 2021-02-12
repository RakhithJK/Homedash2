package com.ftpix.homedash.plugins.couchpotato;

import com.ftpix.homedash.models.ModuleExposedData;
import com.ftpix.homedash.models.WebSocketMessage;
import com.ftpix.homedash.plugins.Plugin;
import com.ftpix.homedash.plugins.couchpotato.apis.CouchPotatoApi;
import com.ftpix.homedash.plugins.couchpotato.apis.MovieProviderAPI;
import com.ftpix.homedash.plugins.couchpotato.apis.RadarrApi;
import com.ftpix.homedash.plugins.couchpotato.models.ImagePath;
import com.ftpix.homedash.plugins.couchpotato.models.MovieObject;
import com.ftpix.homedash.plugins.couchpotato.models.MovieRequest;
import com.ftpix.homedash.plugins.couchpotato.models.Type;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Created by gz on 22-Jun-16.
 *
 */
public class CouchPotatoPlugin extends Plugin {
    public static final String URL = "url", API_KEY = "apiKey", TYPE = "type";
    public static final int THUMB_SIZE = 500;

    public static final String METHOD_SEARCH_MOVIE = "searchMovie", METHOD_MOVIE_LIST = "movieList", METHOD_ADD_MOVIE = "addMovie", METHOD_GET_QUALITIES = "qualities", METHOD_GET_FOLDERS = "folders";

    private final String IMAGE_PATH = "images/";
    private final ImagePath imagePath = () -> {
        try {
            return getCacheFolder().resolve(IMAGE_PATH).toString();
        } catch (IOException e) {
            return "";
        }
    };
    private MovieProviderAPI movieAPI = null;


    @Override
    public String getId() {
        return "couchpotato";
    }

    @Override
    public String getDisplayName() {
        return "CouchPotato/Radarr";
    }

    @Override
    public String getDescription() {
        return "Add movies to your CouchPotato/Radarr wanted list";
    }

    @Override
    public String getExternalLink() {
        return movieAPI.getBaseUrl();
    }

    @Override
    protected void init() {
        logger().info("Initiating Couchpotato plugin.");

        movieAPI = createMovieProviderApiFromSettings(settings);

    }

    @Override
    public String[] getSizes() {
        return new String[]{"1x1", "1x3", "2x1", "2x2", "2x3", "3x2", "3x3",};
    }

    @Override
    public int getBackgroundRefreshRate() {
        return 0;
    }

    @Override
    protected WebSocketMessage processCommand(String command, String message, Object extra) {
        WebSocketMessage response = new WebSocketMessage();
        if (command.equalsIgnoreCase(METHOD_SEARCH_MOVIE)) {
            try {
                response.setMessage(searchMovie(message));
                response.setCommand(METHOD_MOVIE_LIST);
            } catch (Exception e) {
                logger().error("Error while searching movie", e);
                response.setCommand(WebSocketMessage.COMMAND_ERROR);
                response.setMessage("Error while searching movie.");
            }
        } else if (command.equalsIgnoreCase(METHOD_ADD_MOVIE)) {
            try {
                MovieRequest movieObject = gson.fromJson(message, MovieRequest.class);
                addMovie(movieObject);

                response.setCommand(WebSocketMessage.COMMAND_SUCCESS);
                response.setMessage("Movie added successfully !");
            } catch (Exception e) {
                logger().error("Error while searching movie", e);
                response.setCommand(WebSocketMessage.COMMAND_ERROR);
                response.setMessage("Error while Adding movie.");
            }
        } else if (command.equalsIgnoreCase(METHOD_GET_QUALITIES)) {
            try {
                response.setMessage(movieAPI.getQualityProfiles());
                response.setCommand(METHOD_GET_QUALITIES);
            } catch (Exception e) {
                logger().error("Error while getting qualities", e);
                response.setCommand(WebSocketMessage.COMMAND_ERROR);
                response.setMessage("Error while getting qualities.");
            }
        } else if (command.equalsIgnoreCase(METHOD_GET_FOLDERS)) {
            try {
                response.setMessage(movieAPI.getMoviesRootFolder());
                response.setCommand(METHOD_GET_FOLDERS);
            } catch (Exception e) {
                logger().error("Error while getting folders", e);
                response.setCommand(WebSocketMessage.COMMAND_ERROR);
                response.setMessage("Error while getting folders.");
            }
        }
        return response;
    }

    @Override
    public void doInBackground() {

    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    protected Object refresh(String size) throws Exception {
        Map<String, String> map = new HashMap<>();
        map.put("poster", movieAPI.getRandomWantedPoster());
        map.put("name", movieAPI.getName());
        return map;
    }

    @Override
    public int getRefreshRate(String size) {
        return ONE_MINUTE * 10;
    }

    @Override
    public Map<String, String> validateSettings(Map<String, String> settings) {
        //TODO create new movie API
        MovieProviderAPI movieAPI = createMovieProviderApiFromSettings(settings);
        return movieAPI.validateSettings();
    }

    @Override
    public ModuleExposedData exposeData() {
        try {
            ModuleExposedData data = new ModuleExposedData();

            File f = getImagePath().toFile();

            FilenameFilter filter = (dir, name) -> name.matches("([^\\s]+(\\.(?i)(jpg|png|gif|bmp))$)");

            List<File> filesArray = Arrays.asList(f.listFiles(filter));
            Collections.shuffle(filesArray);
            if (!filesArray.isEmpty()) {
                data.addImage(getImagePath() + filesArray.get(0).getName());
            }

            return data;
        } catch (Exception e) {
            logger().error("Couldn't get images", e);
            return null;
        }
    }

    @Override
    public Map<String, String> exposeSettings() {
        return null;
    }

    @Override
    protected void onFirstClientConnect() {

    }

    @Override
    protected void onLastClientDisconnect() {

    }


    @Override
    protected Map<String, Object> getSettingsModel() {
        return null;
    }

    ////////////
    /// Plugin methds
    ///

    private Path getImagePath() throws IOException {
        Path resolve = getCacheFolder().resolve(IMAGE_PATH);
        if (!java.nio.file.Files.exists(resolve)) {
            java.nio.file.Files.createDirectories(resolve);
        }
        return resolve;
    }


    private void addMovie(MovieRequest movieRequest) throws Exception {
        movieAPI.addMovie(movieRequest);
    }


    /**
     * Search a movie from couchpotato instance
     */
    private List<MovieObject> searchMovie(String query) throws Exception {
        return movieAPI.searchMovie(query);
    }


    /**
     * Generates an API client based on the settings
     *
     * @param settings
     * @return
     */
    private MovieProviderAPI createMovieProviderApiFromSettings(Map<String, String> settings) {

        Type type = Type.valueOf(settings.get(TYPE));

        String apiKey = settings.get(API_KEY);
        String url = settings.get(URL);

        if (!url.endsWith("/")) {
            url += "/";
        }

        if (!url.startsWith("http")) {
            url = "http://" + url;
        }

        return switch (type){
            case RADARR -> new RadarrApi(url,apiKey,imagePath, this::getCacheFileUrlPath);
            case COUCHPOTATO -> new CouchPotatoApi(url, apiKey, imagePath, this::getCacheFileUrlPath);
        };
    }
}
