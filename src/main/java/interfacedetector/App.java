package interfacedetector;

import java.nio.file.Path;
import java.util.*;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes every interface in a project, with its implementations, to JSON.
 *
 * Usage: App <scanDir> [output.json]
 */
public class App {
    public static void main(String[] args) throws Exception {
        Path scanDir = Path.of(args[0]);
        Path outputFile = args.length > 1 ? Path.of(args[1]) : Path.of("output.json");

        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(scanDir);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedDirectory", scanDir.toAbsolutePath().toString());
        report.put("totalUniqueInterfaces", interfaces.size());
        report.put("interfaces", new TreeMap<>(interfaces).values().stream().map(InterfaceInfo::toJson).toList());

        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), report);
        System.out.println("Total unique interfaces detected: " + interfaces.size());
        System.out.println("Output written to: " + outputFile.toAbsolutePath());
    }
}
