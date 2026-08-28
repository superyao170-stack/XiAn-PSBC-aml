-- Expose the structured-case risk event knowledge base under system management.
INSERT INTO sys_menu(parent_id,menu_name,path,component,icon,sort_order,type,permission,visible)
SELECT parent.id,'事件元数据管理','/system/event-metadata',
       'views/system/EventMetadata.vue','el-icon-collection',3,'MENU',
       'system:event-metadata:view',true
FROM sys_menu parent
WHERE parent.path='/system'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/system/event-metadata');

UPDATE sys_menu
SET menu_name='事件元数据管理',
    component='views/system/EventMetadata.vue',
    sort_order=3,
    permission='system:event-metadata:view',
    visible=true,
    updated_at=CURRENT_TIMESTAMP
WHERE path='/system/event-metadata';
