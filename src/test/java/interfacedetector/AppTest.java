package interfacedetector;

import org.junit.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AppTest {

    private static final Path TEST1 = Path.of("src/test/resources/test1");
    private static final Path TEST2 = Path.of("src/test/resources/test2");
    private static final Path TEST3 = Path.of("src/test/resources/test3");
    private static final Path TEST4 = Path.of("src/test/resources/test4");
    private static final Path TEST6 = Path.of("src/test/resources/test6");
    private static final Path TEST7 = Path.of("src/test/resources/test7");

    // test basic concrete implementation
    @Test
    public void testImplementorCounts() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST1, concreteImpl, abstractImpl);

        assertEquals(2, concreteImpl.get("Shape").size());
        assertEquals(1, concreteImpl.get("Hamburger").size());
        assertEquals(0, concreteImpl.get("Animal").size());
    }

    // tests that abstract implementations are not counted as concrete
    @Test
    public void testImplementorCounts_program2() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST2, concreteImpl, abstractImpl);

        assertEquals(0, concreteImpl.get("Flyable").size());
        assertEquals(1, concreteImpl.get("Swimmable").size());
        assertEquals(2, concreteImpl.get("Driveable").size());
    }

    // tests that enums can implement interfaces
    @Test
    public void testImplementorCounts_program3() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST3, concreteImpl, abstractImpl);

        assertEquals(1, concreteImpl.get("Week").size());
    }

    // test java built-in interfaces aren't counted
    @Test
    public void testBuiltinInterfaces_program4() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST4, concreteImpl, abstractImpl);

        assertEquals(1, concreteImpl.get("Animal").size());
        assertNull(concreteImpl.get("Comparable"));
    }

    // test interface visibility is captured
    @Test
    public void testInterfaceVisibility() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        Map<String, String> interfaceFilePaths = new HashMap<>();
        Map<String, String> interfaceVisibilities = new HashMap<>();

        App.buildImplementorsMaps(TEST1, concreteImpl, abstractImpl, interfaceFilePaths, interfaceVisibilities);

        assertEquals("public", interfaceVisibilities.get("Shape"));
        assertEquals("public", interfaceVisibilities.get("Hamburger"));
    }

    // test that interfaces with zero declared methods (marker/constant interfaces) are captured
    @Test
    public void testMethodlessInterfaces_program6() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        Map<String, String> interfaceFilePaths = new HashMap<>();
        Map<String, String> interfaceVisibilities = new HashMap<>();
        Map<String, List<String>> extendedInterfaces = new HashMap<>();
        Map<String, Integer> methodCounts = new HashMap<>();

        App.buildImplementorsMaps(TEST6, concreteImpl, abstractImpl, interfaceFilePaths, interfaceVisibilities,
                extendedInterfaces, methodCounts);

        assertEquals(0, (int) methodCounts.get("Marker"));
        assertEquals(0, (int) methodCounts.get("Constants"));
        assertEquals(1, (int) methodCounts.get("Sized"));
    }

    // tests that records can implement interfaces
    @Test
    public void testRecordImplementations_program7() {
        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST7, concreteImpl, abstractImpl);

        assertEquals(1, concreteImpl.get("Shape").size());
        assertEquals(0, abstractImpl.get("Shape").size());
    }
}
