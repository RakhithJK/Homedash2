package com.ftpix.homedash.models.export;

import com.ftpix.homedash.models.Module;
import com.ftpix.homedash.models.ModuleLocation;
import com.ftpix.homedash.models.Page;

import java.util.HashMap;
import java.util.Map;

public class ModuleExport {
    public int page;
    public int order;
    public String pluginClass;
    public boolean onKiosk;


    public Map<String, String> settings = new HashMap<>();

    public ModuleLocation location;


    public static ModuleExport fromModel(Module module) {
        ModuleExport export = new ModuleExport();

        export.page = module.getPage().getId();
        export.pluginClass = module.getPluginClass();
        export.onKiosk = module.isOnKiosk();
        export.order = module.getOrder();


        module.getSettings()
                .forEach(s -> export.settings.put(s.getName(), s.getValue()));

        export.location = module.getLocation();

        return export;
    }

    public static Module toModel(ModuleExport export) {
        Module module = new Module();
        module.setPluginClass(export.pluginClass);
        module.setOnKiosk(export.onKiosk);
        module.setLocation(export.location);
        module.setOrder(export.order);

        Page page = new Page();
        page.setId(export.page);

        module.setPage(page);


        return module;
    }
}
