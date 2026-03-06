import java.util.List;

public class Home {
    private String name;
    private List<Device> devices;

    public Home(String name, String owner) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public void addDevice(Device device) {
        devices.add(device);
    }

}
