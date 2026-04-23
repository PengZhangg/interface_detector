package interfacedetector;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AppTest {

    private static final Path TEST_FOLDER = Path.of("src/test/resources/testProgram_1");
    private static final Path TEST_FOLDER_2 = Path.of("src/test/resources/testProgram_2");
    private static final Path TEST_FOLDER_3 = Path.of("src/test/resources/testProgram_3");

    // test basic concrete implementation
    @Test
    public void testImplementorCounts() throws Exception {
        Map<String, List<String>> result = App.buildImplementorsMap(TEST_FOLDER);

        assertEquals(2, result.get("Shape").size());

        assertEquals(1, result.get("Hamburger").size());
        
        assertEquals(0, result.get("Animal").size());
    }

    // tests that abstract implements are not counted 
    @Test
    public void testImplementorCounts_program2() throws Exception {
        Map<String, List<String>> result = App.buildImplementorsMap(TEST_FOLDER_2);

        assertEquals(0, result.get("Flyable").size());

        assertEquals(1, result.get("Swimmable").size());

        assertEquals(2, result.get("Driveable").size());
    }

    // tests that enums can implement interfaces
    @Test
    public void testImplementorCounts_program3() throws Exception {
        Map<String, List<String>> result = App.buildImplementorsMap(TEST_FOLDER_3);

        assertEquals(1, result.get("Week").size());
    }
}