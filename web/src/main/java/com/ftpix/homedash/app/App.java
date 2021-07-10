package com.ftpix.homedash.app;

import com.ftpix.homedash.app.controllers.SettingsController;
import com.ftpix.homedash.jobs.BackgroundRefresh;
import com.ftpix.homedash.plugins.SystemInfoPlugin;
import com.ftpix.homedash.websocket.FullScreenWebSocket;
import com.ftpix.homedash.websocket.MainWebSocket;
import com.google.common.io.Files;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static spark.Spark.*;

/**
 * Hello world!
 */
public class App {
    private final static String NATIVE_LIBS_FOLDER_NAME = "native-libs";
    private static Logger logger = LogManager.getLogger();

    public static void main(String[] args) {
        try {

            if (args.length > 0 && args[0].equalsIgnoreCase("-create-config")) {
                createDefaultConfig();
            } else {
                URL resource = App.class.getResource("/");

                loadNativeLibs();

//            staticFileLocation("/web");

                port(Constants.PORT);

                if (Constants.SECURE && Constants.KEY_STORE != null && Constants.KEY_STORE_PASS != null) {
                    logger.info("Starting in secure mode using keystore:[{}]", Constants.KEY_STORE);
                    secure(Constants.KEY_STORE, Constants.KEY_STORE_PASS, null, null);
                }

                webSocket("/ws", MainWebSocket.class);
                webSocket("/ws-full-screen", FullScreenWebSocket.class);

                //No cache policy, especially against Edge and IE
                before((req, res) -> {
                    res.header("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1.
                    res.header("Pragma", "no-cache"); // HTTP 1.0.
                    res.header("Expires", "0"); // Proxies.
                    res.header("Access-Control-Allow-Origin", "*");
                    res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
                    res.header("Access-Control-Allow-Headers", "*");
                    res.header("Access-Control-Allow-Credentials", "true");

                    logger.info("{} -> {}", req.requestMethod(), req.url());


                    if (List.of("/css/", "/js/", "/fonts/").stream().anyMatch(s -> req.pathInfo().startsWith(s))) {
                        //skipping any processing on resources
                        return;
                    }

                    if (!req.pathInfo().startsWith("/api")
                            && !req.pathInfo().startsWith("/cache")
                            && !req.pathInfo().equalsIgnoreCase("/login")
                            && !req.pathInfo().equalsIgnoreCase("/config")
                            && !SettingsController.INSTANCE.checkSession(req, res)) {
                        res.redirect("/login");
                    }
                });


                Optional.ofNullable(System.getenv("CONFIG"))
                        .map(String::trim)
                        .filter(s -> s.length() > 0)
                        .ifPresent(json -> {
                            try {
                                SettingsController.INSTANCE.importConfig(json);
                            } catch (SQLException e) {
                                logger.error("Couldn't load JSON config, make sure it's correct and restart application", e);
                                System.exit(1);
                            }
                        });


                Endpoints.define();


                // set up the notifications
                SettingsController.INSTANCE.updateNotificationProviders();

//            enableDebugScreen();

                prepareJobs();
            }
        } catch (Exception e) {
            logger.error("Error during startup, we better stop everything", e);
            System.exit(1);
        }
    }

    /**
     * Creates a default config
     */
    private static void createDefaultConfig() {
        var sb = new StringBuilder();

        String generatedString = RandomStringUtils.random(50, true, true);


        sb.append("#Port running the server\n");
        sb.append("port=4567\n");
        sb.append("\n");
        sb.append("\n");


        sb.append("#Where all the cache files (mostly images) are going to be stored");
        sb.append("\n");
        sb.append("cache_path = cache/");
        sb.append("\n");
        sb.append("\n");

        sb.append("# Location of your saved file");
        sb.append("\n");
        sb.append("db_path = ./homedash");
        sb.append("\n");
        sb.append("\n");

        sb.append("# random string used to authentication and other hashing pruposes");
        sb.append("\n");
        sb.append("salt = " + generatedString);
        sb.append("\n");
        sb.append("\n");


        sb.append("#wheter or not to run the server under https");
        sb.append("\n");
        sb.append("secure = false");
        sb.append("\n");
        sb.append("\n");

        sb.append("# Required only if secure = true, more help: https://uwesander.de/using-your-ssl-certificate-for-your-spark-web-application.html");
        sb.append("\n");
        sb.append("       key_store = jks location");
        sb.append("\n");
        sb.append("    key_store_pass = jks password");
        sb.append("\n");


        Path p = Paths.get("homedash.properties");

        if (java.nio.file.Files.exists(p)) {
            System.out.println("Configuration file " + p.toAbsolutePath().toString() + " already exists");
        } else {
            try {
                java.nio.file.Files.write(p, sb.toString().getBytes());
                System.out.println("Configuration created, you can start Homedash using the following command:");
                System.out.println("java -Dconfig.file=" + p.toAbsolutePath().toString() + " -jar Homedash-<current version>.jar");
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Couldn't write file " + p.toAbsolutePath().toString() + " make sure you have write permission on working dir");
            }


        }
    }


    /**
     * Load all the native libs from other modules
     */
    private static void loadNativeLibs() throws Exception {
        logger.info("Loading native libs if any");
        Properties props = System.getProperties();

        File dir = null;

        final File jarFile = new File(App.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        if (jarFile.isFile()) {  // Run with JAR file
            dir = Files.createTempDir();
            logger.info("Created tmp directory [{}]", dir.getAbsolutePath());
            dir.mkdir();
            final String path = NATIVE_LIBS_FOLDER_NAME;
            final JarFile jar = new JarFile(jarFile);
            final Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                final String name = jarEntry.getName();

                if (name.startsWith(path + "/") && !jarEntry.isDirectory()) { //filter according to the path

                    File toCopy = new File(dir.getAbsolutePath() + File.separator + name);
                    if (!toCopy.exists()) {
                        try {
                            logger.info("Copying [{}] to [{}]", name, toCopy.getAbsolutePath());

                            if (!toCopy.getParentFile().exists()) {
                                toCopy.getParentFile().mkdir();
                            }
                            InputStream is = jar.getInputStream(jarEntry); // get the input stream
                            FileOutputStream fos = new FileOutputStream(toCopy);
                            while (is.available() > 0) {  // write contents of 'is' to 'fos'
                                fos.write(is.read());
                            }
                            fos.close();
                            is.close();
                        } catch (Exception e) {
                            logger.error("Error while extracting [" + name + "] continuing but it may cause issues", e);
                        }
                    }
                }
            }
            jar.close();

            dir = new File(dir.getAbsolutePath() + File.separator + NATIVE_LIBS_FOLDER_NAME);
            logger.info("Set lib path [{}]", dir.getAbsolutePath());

        } else {// run in IDE

            URL url = SystemInfoPlugin.class.getClassLoader().getResource(NATIVE_LIBS_FOLDER_NAME);
            if (url != null) {
                dir = new File(url.toURI());
            }

        }

        if (dir != null) {
            logger.info("Setting [{}] as library path", dir.getAbsolutePath());
            props.setProperty("java.library.path", dir.getAbsolutePath());
        }

    }


    /**
     * Create the scheduling jobs for refreshing the modules in the background
     */
    private static void prepareJobs() throws SchedulerException {
        SchedulerFactory sf = new StdSchedulerFactory();
        Scheduler scheduler = sf.getScheduler();

        Date runTime = DateBuilder.evenMinuteDate(new Date());

        JobDetail job = JobBuilder.newJob(BackgroundRefresh.class).withIdentity("BackgroundRefresh", "HomeDash").build();

        Trigger trigger = TriggerBuilder.newTrigger().withIdentity("BackgroundRefresh", "HomeDash").withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever())
                .build();

        scheduler.scheduleJob(job, trigger);
        logger.info(job.getKey() + " will run at: " + runTime);

        scheduler.start();
    }
}
