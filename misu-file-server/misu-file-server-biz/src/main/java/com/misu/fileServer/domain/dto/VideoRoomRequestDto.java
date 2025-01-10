package com.misu.fileServer.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 放映室获取实体
 *
 * @author misu
 */
@Data
@NoArgsConstructor
public class VideoRoomRequestDto {

    @NotNull(message = "放映室id不能为空")
    private String roomId;
}
