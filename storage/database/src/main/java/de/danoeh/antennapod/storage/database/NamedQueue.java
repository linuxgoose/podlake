package de.danoeh.antennapod.storage.database;

public class NamedQueue {
    private final long id;
    private final String name;

    public NamedQueue(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}

