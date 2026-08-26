
package com.datagraph.bank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagraph.bank.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    List<SysMenu> selectMenuByRoleCode(String roleCode);
}
