package interfacedetector;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AppTest {

    private static final Path TEST_FOLDER = Path.of("src/test/resources/testProgram_1");

    @Test
    public void testImplementorCounts() throws Exception {
        Map<String, List<String>> result = App.buildImplementorsMap(TEST_FOLDER);

        assertEquals(2, result.get("Shape").size());

        assertEquals(1, result.get("Hamburger").size());
        
        assertEquals(0, result.get("Animal").size());
    }
}