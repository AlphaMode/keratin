package net.ornithemc.keratin.api.task.unpick;

public enum UnpickVersion {
    V1("v1"),
    V2("v2"),
    V3("unpick v3"),
    V4("unpick v4");

    private final String header;

    UnpickVersion(String header) {
        this.header = header;
    }

    public String getHeader() {
        return header;
    }

    public static UnpickVersion getVersion(int version) {
        return switch (version) {
            case 1 -> UnpickVersion.V1;
            case 2 -> UnpickVersion.V2;
            case 3 -> UnpickVersion.V3;
            case 4 -> UnpickVersion.V4;
            default -> null;
        };
    }

    public static UnpickVersion parse(String version) {
        return switch (version) {
            case "v1" -> UnpickVersion.V1;
            case "v2" -> UnpickVersion.V2;
            case "unpick v3" -> UnpickVersion.V3;
            case "unpick v4" -> UnpickVersion.V4;
            default -> null;
        };
    }
}
