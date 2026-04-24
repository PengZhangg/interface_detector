package interfacedetector;

import org.junit.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.assertEquals;

public class AppTest {

    private static final Path TEST_FOLDER = Path.of("src/test/resources/testProgram_1");
    private static final Path TEST_FOLDER_2 = Path.of("src/test/resources/testProgram_2");
    private static final Path TEST_FOLDER_3 = Path.of("src/test/resources/testProgram_3");

    // test basic concrete implementation
    @Test
    public void testImplementorCounts() {
        Map<String, List<String>> concreteImpl = new HashMap<>();
        Map<String, List<String>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST_FOLDER, concreteImpl, abstractImpl);

        assertEquals(2, concreteImpl.get("Shape").size());
        assertEquals(1, concreteImpl.get("Hamburger").size());
        assertEquals(0, concreteImpl.get("Animal").size());
    }

    // tests that abstract implementations are not counted as concrete
    @Test
    public void testImplementorCounts_program2() {
        Map<String, List<String>> concreteImpl = new HashMap<>();
        Map<String, List<String>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST_FOLDER_2, concreteImpl, abstractImpl);

        assertEquals(0, concreteImpl.get("Flyable").size());
        assertEquals(1, concreteImpl.get("Swimmable").size());
        assertEquals(2, concreteImpl.get("Driveable").size());
    }

    // tests that enums can implement interfaces
    @Test
    public void testImplementorCounts_program3() {
        Map<String, List<String>> concreteImpl = new HashMap<>();
        Map<String, List<String>> abstractImpl = new HashMap<>();
        App.buildImplementorsMaps(TEST_FOLDER_3, concreteImpl, abstractImpl);

        assertEquals(1, concreteImpl.get("Week").size());
    }
}
