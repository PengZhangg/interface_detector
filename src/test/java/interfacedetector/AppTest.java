package interfacedetector;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppTest {

    private Path resource(String filename) {
        return Path.of("src/test/resources/testProgram_1/" + filename);
    }

    private boolean isInterface(String filename) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(resource(filename));
        ClassOrInterfaceDeclaration decl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        return decl.isInterface();
    }

    @Test
    public void abstractShapeIsNotInterface() throws Exception {
        assertFalse(isInterface("AbstractShape.java"));
    }

    @Test
    public void bigMacIsNotInterface() throws Exception {
        assertFalse(isInterface("BigMac.java"));
    }

    @Test
    public void circleIsNotInterface() throws Exception {
        assertFalse(isInterface("Circle.java"));
    }

    @Test
    public void rectangleIsNotInterface() throws Exception {
        assertFalse(isInterface("Rectangle.java"));
    }

    @Test
    public void hamburgerIsInterface() throws Exception {
        assertTrue(isInterface("Hamburger.java"));
    }

    @Test
    public void animalIsInterface() throws Exception {
        assertTrue(isInterface("Animal.java"));
    }

    @Test
    public void shapeIsInterface() throws Exception {
        assertTrue(isInterface("Shape.java"));
    }
}
