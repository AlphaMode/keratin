package net.ornithemc.keratin.api.task.unpick;

import daomephsta.unpick.constantmappers.datadriven.parser.v3.UnpickV3Remapper;
import daomephsta.unpick.constantmappers.datadriven.tree.UnpickV3Visitor;

import java.util.List;
import java.util.Map;

public class UnpickV3UnestingRemapper extends UnpickV3Remapper {
    private final Map<String, String> classMappings;

    public UnpickV3UnestingRemapper(Map<String, String> classMappings, UnpickV3Visitor downstream) {
        super(downstream);
        this.classMappings = classMappings;
    }

    @Override
    protected String mapClassName(String className) {
        return classMappings.getOrDefault(className, className);
    }

    @Override
    protected String mapFieldName(String className, String fieldName, String fieldDesc) {
        throw new RuntimeException("TODO");
    }

    @Override
    protected String mapMethodName(String className, String methodName, String methodDesc) {
        throw new RuntimeException("TODO");
    }

    @Override
    protected List<String> getClassesInPackage(String pkg) {
        throw new RuntimeException("TODO");
    }

    @Override
    protected String getFieldDesc(String className, String fieldName) {
        throw new RuntimeException("TODO");
    }
}
