package com.ftpix.homedash.db.schemaManagement.updates;

import com.ftpix.homedash.db.schemaManagement.UpdateStep;
import com.ftpix.homedash.models.Version;

import java.util.ArrayList;
import java.util.List;

public class Update20200710 implements UpdateStep {
    @Override
    public List<String> ups() {
        List<String> statements = new ArrayList<>();

        statements.add("ALTER TABLE modules ADD COLUMN IF NOT EXISTS `order` INT DEFAULT 0");

        return statements;
    }

    @Override
    public Version getVersion() {
        return new Version("2020.07.10");
    }
}
