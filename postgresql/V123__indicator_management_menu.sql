-- Static indicator catalogue under system management.
INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT parent.id,'指标管理','/system/indicators',
       'views/system/IndicatorManagement.vue','el-icon-data-analysis',4,'MENU',
       'system:indicators:view',true
FROM sys_menu parent
WHERE parent.path='/system'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/system/indicators');

UPDATE sys_menu
SET menu_name='指标管理',component='views/system/IndicatorManagement.vue',sort_order=4,
    permission='system:indicators:view',visible=true,updated_at=CURRENT_TIMESTAMP
WHERE path='/system/indicators';
