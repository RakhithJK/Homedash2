package com.ftpix.homedash.app.controllers;


import com.ftpix.homedash.app.PluginModuleMaintainer;
import com.ftpix.homedash.db.DB;
import com.ftpix.homedash.jobs.BackgroundRefresh;
import com.ftpix.homedash.models.Module;
import com.ftpix.homedash.models.*;
import com.ftpix.homedash.plugins.Plugin;
import com.ftpix.homedash.utils.HomeDashTemplateEngine;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.Where;
import io.gsonfire.GsonFireBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpStatus;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.lang.reflect.Type;
import java.security.InvalidParameterException;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static com.ftpix.homedash.db.DB.MODULE_DAO;
import static com.ftpix.homedash.db.DB.MODULE_SETTINGS_DAO;

public enum ModuleController implements Controller<Module, Integer> {
    INSTANCE;

    public static final String SESSION_NEW_MODULE_PAGE = "new-module-page";
    private final Gson gson = new GsonFireBuilder().enableExposeMethodResult().createGsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private Logger logger = LogManager.getLogger();

    public void defineEndpoints() {

        /*
         * Add module
         */
        Spark.get("/add-module/on-page/:page", this::addModuleOnPage, new HomeDashTemplateEngine());

        Spark.get("/plugins", this::listPlugins, gson::toJson);
        Spark.post("/plugins/:page", this::saveModule, gson::toJson);

        /*
         * Add module with class This will save the module if there are no
         * settings to display, otherwise it'll show the settings
         */
        Spark.get("/add-module/:pluginclass", this::addModule, new HomeDashTemplateEngine());

        /*
         * Add module
         */
        Spark.get("/module/:moduleId/settings", this::getModuleSettings, new HomeDashTemplateEngine());


        /*
         * Deletes a module
         */
        Spark.delete("/module/:moduleId", this::deleteModule);

        Spark.get("/module/:moduleId/move-to-page/:pageId", this::moveModuleToPage, gson::toJson);

        Spark.get("/module/for-page/:pageId", this::getForPage, gson::toJson);
        Spark.get("/module/set-order/:pageId", this::setOrderForPage, gson::toJson);
        Spark.get("/module/:moduleId/move/:forward/page/:pageId", this::moveModule, gson::toJson);
    }

    @Override
    public Module get(Integer id) throws SQLException {
        return DB.MODULE_DAO.queryForId(id);
    }

    @Override
    public List<Module> getAll() throws SQLException {
        return DB.MODULE_DAO.queryForAll();
    }

    @Override
    public boolean deleteById(Integer id) throws Exception {
        return delete(get(id));
    }

    @Override
    public boolean delete(Module object) throws Exception {
        deleteModuleLayoutAndSettings(object);
        DB.MODULE_DATA_DAO.delete(object.getData());
        return DB.MODULE_DAO.delete(object) == 1;
    }

    @Override
    public boolean update(Module object) throws SQLException {
        return DB.MODULE_DAO.update(object) == 1;
    }

    @Override
    public Integer create(Module object) throws SQLException {
        DB.MODULE_DAO.create(object);
        return object.getId();
    }

    private List<Module> moveModule(Request req, Response response) throws SQLException {
        int moduleId = Integer.parseInt(Optional.ofNullable(req.params("moduleId")).orElseThrow(() -> new InvalidParameterException("Module id must be an int")));
        int pageId = Integer.parseInt(Optional.ofNullable(req.params("pageId")).orElse("1"));
        boolean forward = Optional.ofNullable(req.params("forward")).orElse("true").equalsIgnoreCase("true");

        List<Module> modules = getModulesForPage(pageId);
        if (modules.size() == 1) {
            return modules;
        }
        //making sure it's all in order

        int indexToMove = -1;
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            m.setOrder(i);
            if (m.getId() == moduleId) {
                indexToMove = i;
            }
        }

        if (forward && indexToMove < modules.size() - 1) {
            Collections.swap(modules, indexToMove, indexToMove + 1);
        } else if (!forward && indexToMove > 0) {
            Collections.swap(modules, indexToMove, indexToMove - 1);
        }

        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            m.setOrder(i);
            MODULE_DAO.update(m);
        }

        return modules;
    }

    private List<Module> setOrderForPage(Request request, Response response) throws SQLException {

        Type moduleType = new TypeToken<ArrayList<Module>>() {
        }.getType();
        List<Module> modules = gson.fromJson(request.body(), moduleType);

        modules.forEach(m -> {
            try {
                MODULE_DAO.update(m);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        return getForPage(request, response);
    }

    private List<PluginSimple> listPlugins(Request request, Response response) {
        return PluginController.INSTANCE.listAvailablePlugins().stream()
                .map(p -> new PluginSimple(p.getClass().getName(), p.getDisplayName(), p.getDescription(), p.hasSettings()))
                .collect(Collectors.toList());
    }

    /**
     * Moves a module to a different page
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return
     * @throws SQLException
     */
    private boolean moveModuleToPage(Request req, Response res) throws SQLException {
        int moduleId = Integer.parseInt(req.params("moduleId"));
        int pageId = Integer.parseInt(req.params("pageId"));

        logger.info("get /module/{}/move-to-page/{}", moduleId, pageId);

        Module module = DB.MODULE_DAO.queryForId(moduleId);
        Page page = DB.PAGE_DAO.queryForId(pageId);

        if (page != null && module != null) {
            module.setPage(page);
            DB.MODULE_DAO.update(module);
            return true;
        } else {
            return false;
        }
    }

    /**
     * deletes a module
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return a boolean telling if the deletion has been successful.
     */
    private boolean deleteModule(Request req, Response res) {

        int moduleId = Integer.parseInt(req.params("moduleId"));

        logger.info("/delete-module/{}", moduleId);
        try {
            deleteById(moduleId);

//                res.redirect("/");
            return true;
        } catch (Exception e) {
            logger.error("Error while deleting module", e);
            res.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
            return false;
        }
    }

    /**
     * Save of update a module
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return the template for the module settings if necessary, redirect to index if the module has no settings.
     */
    private Map<String, String> saveModule(Request req, Response res) {
        int page = Integer.parseInt(Optional.ofNullable(req.params("page")).orElse("1"));

        logger.info("/save-module");
        try {

            //find page
            logger.info("/save-module ({} params)", req.queryMap().toMap().size());

            //checking settings
            //flattening post query
            Map<String, String> flatSettings = new HashMap<String, String>();
            req.queryMap().toMap().forEach((key, value) -> {
                flatSettings.put(key, value[0]);
            });


            Plugin plugin;
            boolean editing = false;
            if (flatSettings.containsKey("module_id")) {
                editing = true;
                plugin = PluginModuleMaintainer.INSTANCE.getPluginForModule(Integer.parseInt(flatSettings.get("module_id")));
            } else {
                plugin = PluginController.INSTANCE.createPluginFromClass(flatSettings.get("class"));
            }


            Map<String, String> errors = plugin.validateSettings(flatSettings);
            //No errors, we're good to go
            if (errors == null || errors.size() == 0) {
                saveModuleWithSettings(req.queryMap().toMap(), page);
                return new HashMap<>();
            } else {
                return errors;
            }
        } catch (Exception e) {
            logger.error("Error while saving module", e);
        }
        return new HashMap<>();
    }

    /**
     * Get and displays the settings for a module
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return Gets the settings of a module, nothing if we can't get it.
     */
    private ModelAndView getModuleSettings(Request req, Response res) {

        int moduleId = Integer.parseInt(req.params("moduleId"));
        logger.info("/add-module/{}/settings");
        try {
            Plugin plugin = PluginModuleMaintainer.INSTANCE.getPluginForModule(moduleId);

            Map<String, Object> map = new HashMap<>();
            map.put("plugin", plugin);
            map.put("pluginName", plugin.getDisplayName());
            map.put("settings", PluginController.INSTANCE.getPluginSettingsHtml(plugin));

            return new ModelAndView(map, "module-settings");
        } catch (Exception e) {
            logger.error("Couldn't get module settings", e);
            return null;
        }
    }

    /**
     * Adds a module
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return
     * @throws ClassNotFoundException
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws SQLException
     */
    private ModelAndView addModule(Request req, Response res) throws ClassNotFoundException, IllegalAccessException, InstantiationException, SQLException {

        Plugin plugin = (Plugin) Class.forName(req.params("pluginclass")).newInstance();

        logger.info("/add-module/{}", plugin.getClass().getCanonicalName());
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("settings", PluginController.INSTANCE.getPluginSettingsHtml(plugin));
            map.put("pluginClass", plugin.getClass().getCanonicalName());
            map.put("pluginName", plugin.getDisplayName());


            return new ModelAndView(map, "module-settings");
        } catch (Exception e) {
            logger.info("no settings to display we save the module");

            //find page
            int page = 1;
            if (req.session().attribute(SESSION_NEW_MODULE_PAGE) != null) {
                page = req.session().attribute(SESSION_NEW_MODULE_PAGE);
            }

            Map<String, String[]> params = req.queryMap().toMap();
            params.put("class", new String[]{plugin.getClass().getCanonicalName()});
            saveModuleWithSettings(params, page);
            res.redirect("/");
            return null;
        }

    }

    /**
     * Adds a module on a specific page
     *
     * @param req A Spark Request
     * @param res A Spark response
     * @return the compiled template for every plugin available.
     */
    private ModelAndView addModuleOnPage(Request req, Response res) {

        logger.info("/add-module");
        try {
            int pageId = Integer.parseInt(req.params("page"));
            Page p = DB.PAGE_DAO.queryForId(pageId);
            if (p != null) {
                req.session().attribute(SESSION_NEW_MODULE_PAGE, p.getId());

                Map<String, Object> map = new HashMap<>();
                map.put("plugins", PluginController.INSTANCE.listAvailablePlugins());

                return new ModelAndView(map, "add-module");
            } else {
                res.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get all the modules for a specific page
     */
    public List<Module> getModulesForPage(int page) throws SQLException {
        logger.info("Getting modules on page [{}]", page);
        QueryBuilder<Module, Integer> queryBuilder = DB.MODULE_DAO.queryBuilder();
        Where<Module, Integer> where = queryBuilder.where();
        where.eq("page_id", page);

        PreparedQuery<Module> preparedQuery = queryBuilder.prepare();

        return DB.MODULE_DAO.query(preparedQuery);
    }

    public List<Module> getModulesForPage(Page page) throws SQLException {
        return getModulesForPage(page.getId());
    }


    /**
     * Get the plugin class for a module
     */
    public Plugin getModulePlugin(int moduleId) throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException {
        Module module = MODULE_DAO.queryForId(moduleId);

        Plugin plugin = (Plugin) Class.forName(module.getPluginClass()).newInstance();
        return plugin;
    }

    /**
     * Save a module with its settings
     */
    public int saveModuleWithSettings(Map<String, String[]> postParams, int pageId) throws NumberFormatException, SQLException {
        final Module module;
        if (postParams.containsKey("module_id")) {
            logger.info("Editing a module");
            module = MODULE_DAO.queryForId(Integer.parseInt(postParams.get("module_id")[0]));
        } else {
            logger.info("Creating new module");
            module = new Module();
            module.setPluginClass(postParams.get("class")[0]);
            Page page = DB.PAGE_DAO.queryForId(pageId);
            logger.info("using page #[{}]:{}", page.getId(), page.getName());
            module.setPage(page);
        }

        MODULE_DAO.createOrUpdate(module);

        MODULE_SETTINGS_DAO.delete(module.getSettings());

        logger.info("[{}] params found", postParams.size());
        postParams.forEach((name, value) -> {
            try {
                if (!name.equalsIgnoreCase("class")) {
                    logger.info("Adding setting [{}={}]", name, value[0]);
                    ModuleSettings ms = new ModuleSettings();
                    ms.setModule(module);
                    ms.setName(name);
                    ms.setValue(value[0]);
                    MODULE_SETTINGS_DAO.create(ms);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        BackgroundRefresh.resetTimer();

        //Initializing the modules endpoints if any;
        try {
            PluginUrlController.INSTANCE.definePluginEndpoints(PluginModuleMaintainer.INSTANCE.getPluginForModule(module.getId()));
        } catch (Exception e) {
            logger.error("Couldn't define endpoints for module {}", module.getId(), e);
        }

        logger.info("Module saved, id:[{}]", module.getId());
        return module.getId();
    }

    /**
     * Deletes a module
     */
    public boolean deleteModuleLayoutAndSettings(Module module) throws Exception {
        logger.info("deleteModuleLayoutAndSettings({})", module.getId());
        if (module != null) {
            ModuleSettingsController.INSTANCE.deleteMany(module.getSettings());

            PluginModuleMaintainer.INSTANCE.removeModule(module.getId());
            return true;
        } else {
            return false;
        }
    }


    /**
     * Saves a single module data
     */
    public boolean saveModuleData(ModuleData moduleData) throws SQLException {
        getModuleData(moduleData.getModule().getId(), moduleData.getName()).ifPresent((data) -> {
            moduleData.setId(data.getId());
        });
        return DB.MODULE_DATA_DAO.createOrUpdate(moduleData).getNumLinesChanged() == 1;
    }

    /**
     * Deletes a single module data
     */
    public boolean deleteModuleData(ModuleData moduleData) throws SQLException {
        getModuleData(moduleData.getModule().getId(), moduleData.getName()).ifPresent((data) -> {
            moduleData.setId(data.getId());
        });
        return DB.MODULE_DATA_DAO.delete(moduleData) == 1;
    }


    /**
     * Gets  module data by moduleid and name
     */
    public Optional<ModuleData> getModuleData(int moduleId, String name) throws SQLException {
        QueryBuilder<ModuleData, Integer> queryBuilder = DB.MODULE_DATA_DAO.queryBuilder();
        Where<ModuleData, Integer> where = queryBuilder.where();
        where.eq("module_id", moduleId).and().eq("name", name);

        PreparedQuery<ModuleData> preparedQuery = queryBuilder.prepare();


        ModuleData data = DB.MODULE_DATA_DAO.queryForFirst(preparedQuery);
        if (data != null) {
            return Optional.of(data);
        } else {
            return Optional.empty();
        }
    }

    public List<Module> getForPage(Page page) throws SQLException {
        QueryBuilder<Module, Integer> queryBuilder = DB.MODULE_DAO.queryBuilder();
        Where<Module, Integer> where = queryBuilder.where();
        where.eq("page_id", page.getId());
        queryBuilder.orderBy("order", true);

        PreparedQuery<Module> preparedQuery = queryBuilder.prepare();

        return DB.MODULE_DAO.query(preparedQuery);
    }

    private List<Module> getForPage(Request req, Response response) throws SQLException {
        int pageId = Integer.parseInt(Optional.ofNullable(req.params("page")).orElse("1"));
        final Page page = PageController.INSTANCE.get(pageId);

        return getForPage(page);
    }
}
