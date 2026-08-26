
package com.datagraph.bank.dto;

import lombok.Data;

import java.util.List;

@Data
public class MenuDTO {

    private Long id;
    private Long parentId;
    private String menuName;
    private String path;
    private String component;
    private String icon;
    private Integer sortOrder;
    private String type;
    private String permission;
    private List<MenuDTO> children;
}
