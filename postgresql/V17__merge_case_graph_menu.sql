UPDATE sys_menu SET visible=false, updated_at=CURRENT_TIMESTAMP
WHERE path='/case/graph' AND deleted=false;
