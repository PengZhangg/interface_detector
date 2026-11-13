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

    private static int concrete(Map<String, InterfaceInfo> interfaces, String name) {
        return interfaces.get(name).concreteImplementations().size();
    }

    // test basic concrete implementation
    @Test
    public void testImplementorCounts() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST1);

        assertEquals(2, concrete(interfaces, "Shape"));
        assertEquals(1, concrete(interfaces, "Hamburger"));
        assertEquals(0, concrete(interfaces, "Animal"));
    }

    // tests that abstract implementations are not counted as concrete
    @Test
    public void testImplementorCounts_program2() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST2);

        assertEquals(0, concrete(interfaces, "Flyable"));
        assertEquals(1, concrete(interfaces, "Swimmable"));
        assertEquals(2, concrete(interfaces, "Driveable"));
    }

    // tests that enums can implement interfaces
    @Test
    public void testImplementorCounts_program3() {
        assertEquals(1, concrete(InterfaceScanner.scan(TEST3), "Week"));
    }

    // test java built-in interfaces aren't counted
    @Test
    public void testBuiltinInterfaces_program4() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST4);

        assertEquals(1, concrete(interfaces, "Animal"));
        assertNull(interfaces.get("Comparable"));
    }

    // test interface visibility is captured
    @Test
    public void testInterfaceVisibility() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST1);

        assertEquals("public", interfaces.get("Shape").visibility());
        assertEquals("public", interfaces.get("Hamburger").visibility());
    }

    // test that interfaces with zero declared methods (marker/constant interfaces) are captured
    @Test
    public void testMethodlessInterfaces_program6() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST6);

        assertEquals(0, interfaces.get("Marker").methodCount());
        assertEquals(0, interfaces.get("Constants").methodCount());
        assertEquals(1, interfaces.get("Sized").methodCount());
    }

    // tests that records can implement interfaces
    @Test
    public void testRecordImplementations_program7() {
        Map<String, InterfaceInfo> interfaces = InterfaceScanner.scan(TEST7);

        assertEquals(1, concrete(interfaces, "Shape"));
        assertEquals(0, interfaces.get("Shape").abstractImplementations().size());
    }
}
