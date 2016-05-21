package com.ftpix.homedash.app;

import static spark.Spark.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.SQLException;
import java.util.Date;
import java.util.Properties;
import java.util.Scanner;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.DateBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import com.ftpix.homedash.db.DB;
import com.ftpix.homedash.jobs.BackgroundRefresh;
import com.ftpix.homedash.models.Layout;
import com.ftpix.homedash.models.Page;
import com.ftpix.homedash.plugins.systeminfo.SystemInfoPlugin;
import com.ftpix.homedash.websocket.MainWebSocket;

/**
 * Hello world!
 *
 */
public class App {
	private static Logger logger = LogManager.getLogger();

	public static void main(String[] args) {
		try {
			loadNativeLibs();

			
			staticFileLocation("/web");

			port(Constants.PORT);

			webSocket("/ws", MainWebSocket.class);

			createDefaultData();
			Endpoints.define();
			Endpoints.pluginResources();
			
			prepareJobs();

		} catch (Exception e) {
			logger.error("Error during startupm, we better stop everything", e);
			System.exit(1);
		}
	}

	/**
	 * Load all the native libs from other modules
	 * 
	 * @throws URISyntaxException
	 */
	private static void loadNativeLibs() throws URISyntaxException {
		logger.info("Loading native libs if any");
		Properties props = System.getProperties();
		URL url = SystemInfoPlugin.class.getClassLoader().getResource("native-libs");
		File dir;
		dir = new File(url.toURI());
		for (String file : dir.list()) {
			logger.info(file);
		}
		props.setProperty("java.library.path", dir.getAbsolutePath());
	}
	
	/**
	 * Create default data like layouts and the main page
	 * 
	 * @throws SQLException
	 */
	private static void createDefaultData() throws SQLException {
		logger.info("Creating first page if it doesn't exist");
		Page page = new Page();
		page.setId(1);
		page.setName("Main");

		DB.PAGE_DAO.createIfNotExists(page);

		logger.info("Creating the 3 default layouts");
		Layout desktop = new Layout();
		desktop.setId(1);
		desktop.setMaxGridWidth(10);
		desktop.setName("Desktop");

		DB.LAYOUT_DAO.createOrUpdate(desktop);

		Layout tablet = new Layout();
		tablet.setId(2);
		tablet.setMaxGridWidth(5);
		tablet.setName("Tablet");

		DB.LAYOUT_DAO.createOrUpdate(tablet);

		Layout mobile = new Layout();
		mobile.setId(3);
		mobile.setMaxGridWidth(3);
		mobile.setName("Mobile");

		DB.LAYOUT_DAO.createOrUpdate(mobile);

	}

	/**
	 * Create the scheduling jobs for refreshing the modules in the background
	 * 
	 * @throws SchedulerException
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