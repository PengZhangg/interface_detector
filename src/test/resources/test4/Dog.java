public class Dog implements Animal, Comparable<Dog> {

    private String name;

    public Dog(String name) {
        this.name = name;
    }

    @Override
    public void eat() {
        System.out.println(name + " is eating.");
    }

    @Override
    public int compareTo(Dog other) {
        return this.name.compareTo(other.name);
    }
}
