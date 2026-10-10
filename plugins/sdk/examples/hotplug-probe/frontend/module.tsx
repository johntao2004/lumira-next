import { Typography } from 'antd';
import type { FrontendModule } from '@/platform/modules/types';
export const probeFrontendModule={id:'hotplug-probe',version:'1.0.0',required:false,dependencies:['platform'],routes:[{id:'hotplug-probe',label:'plugin',permission:null,group:'operations',order:900}],pages:{'hotplug-probe':()=> <Typography.Title level={4}>全新测试插件已在线加载</Typography.Title>}} satisfies FrontendModule;
