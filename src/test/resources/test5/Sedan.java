public class Sedan implements RoadVehicle {
    @Override
    public String getId() {
        return "sedan";
    }

    @Override
    public int getWheelCount() {
        return 4;
    }
}
