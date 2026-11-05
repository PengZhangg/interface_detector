// records can implement interfaces and are always concrete
public record Square(double side) implements Shape {
    public double area() {
        return side * side;
    }
}
