package interfacedetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One interface declared in the scanned project, and the types that implement it. */
public final class InterfaceInfo {
    private final String name;
    private final String filePath;
    private final String visibility;
    private final String packageName;
    private List<String> extendedInterfaces = List.of();
    private int methodCount;
    private final List<ImplementationEntry> concreteImplementations = new ArrayList<>();
    private final List<ImplementationEntry> abstractImplementations = new ArrayList<>();

    InterfaceInfo(String name, String filePath, String visibility, String packageName) {
        this.name = name;
        this.filePath = filePath;
        this.visibility = visibility;
        this.packageName = packageName;
    }

    public String name() {
        return name;
    }

    public String filePath() {
        return filePath;
    }

    public String visibility() {
        return visibility;
    }

    String packageName() {
        return packageName;
    }

    /** Fully-qualified names where resolvable, otherwise the name as written. */
    public List<String> extendedInterfaces() {
        return extendedInterfaces;
    }

    void setExtendedInterfaces(List<String> parents) {
        this.extendedInterfaces = List.copyOf(parents);
    }

    public int methodCount() {
        return methodCount;
    }

    void setMethodCount(int methodCount) {
        this.methodCount = methodCount;
    }

    public List<ImplementationEntry> concreteImplementations() {
        return concreteImplementations;
    }

    public List<ImplementationEntry> abstractImplementations() {
        return abstractImplementations;
    }

    public boolean isUnimplemented() {
        return concreteImplementations.isEmpty() && abstractImplementations.isEmpty();
    }

    /** The interface's entry in output.json. */
    Map<String, Object> toJson() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("filePath", filePath);
        entry.put("visibility", visibility);
        entry.put("concreteImplementations", concreteImplementations);
        entry.put("abstractImplementations", abstractImplementations);
        entry.put("extendedInterfaces", extendedInterfaces);
        entry.put("methodCount", methodCount);
        return entry;
    }
}
