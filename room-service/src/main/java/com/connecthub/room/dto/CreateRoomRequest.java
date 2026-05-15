package com.connecthub.room.dto;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class CreateRoomRequest {
    @Size(min = 1, max = 100) private String name;
    @Size(max = 500) private String description;
    private String type;
    private boolean isPrivate;
    private int maxMembers = 500;
    private List<Integer> memberIds;
}
