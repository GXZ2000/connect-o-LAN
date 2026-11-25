package model;

import java.util.List;

public record GroupInfo(
    String groupId,
    String groupName,
    String creatorIp,
    List<String> memberIps
) {}