public enum Day implements Week{
    MONDAY, 
    TUESDAY, 
    WEDNESDAY, 
    THURSDAY, 
    FRIDAY, 
    SATURDAY,
    SUNDAY;

    public int day() {
        return this.ordinal() + 1;
     }
}
