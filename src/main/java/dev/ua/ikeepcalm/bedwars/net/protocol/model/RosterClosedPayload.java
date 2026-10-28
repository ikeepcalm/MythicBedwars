package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

import java.util.List;

@Data
public class RosterClosedPayload {

    private final List<String> roster;
    private final int count;
}
