import type { Industry } from '@/api/foundation/types';

/** 后台行业体验只读配置；不得用于授权或计算任何业务事实。 */
export interface IndustryExperienceProfile {
  industry: Industry;
  label: string;
  summary: string;
  primaryActions: readonly string[];
  color: 'green' | 'amber' | 'blue';
}

const profiles: Record<Industry, IndustryExperienceProfile> = {
  CONVENIENCE: {
    industry: 'CONVENIENCE',
    label: '便利店',
    summary: '聚焦快速开店、商品价格发布、交班与高频异常处理。',
    primaryActions: ['商品价格', '终端登记', '经营报表'],
    color: 'green'
  },
  SNACK_DISCOUNT: {
    industry: 'SNACK_DISCOUNT',
    label: '零食折扣店',
    summary: '聚焦多件商品、促销版本、批量导入与库存周转。',
    primaryActions: ['商品价格', '促销会员', '库存成本'],
    color: 'amber'
  },
  COMMUNITY_SUPERMARKET: {
    industry: 'COMMUNITY_SUPERMARKET',
    label: '社区超市',
    summary: '聚焦多单位、称重提示、采购调拨与门店经营对账。',
    primaryActions: ['采购调拨', '库存成本', '经营报表'],
    color: 'blue'
  }
};

/** 解析商业 V1 固定体验；这里只返回展示文案，不推导模板业务内容。 */
export const resolveIndustryExperience = (industry: Industry): Readonly<IndustryExperienceProfile> => Object.freeze(profiles[industry]);

export const INDUSTRY_EXPERIENCE_OPTIONS = (Object.keys(profiles) as Industry[]).map((industry) => ({
  value: industry,
  label: profiles[industry].label
}));
