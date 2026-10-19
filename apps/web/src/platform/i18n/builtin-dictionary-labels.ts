import type { MessageKey } from "./messages";

type Translate = (key: MessageKey) => string;

type Seed = { seed: string; key: MessageKey };

/** Chinese labels published in the platform initialization seed. Edited labels stay as stored. */
const dictionaryNames: Record<string, Seed> = {
  work_order_feedback_status: { seed: "工单反馈状态", key: "dictWorkOrderFeedbackStatus" },
  work_order_feedback_priority: { seed: "工单反馈优先级", key: "dictWorkOrderFeedbackPriority" },
  work_order_feedback_default: { seed: "工单反馈默认配置", key: "dictWorkOrderFeedbackDefault" },
  work_order_feedback_category: { seed: "工单反馈分类", key: "dictWorkOrderFeedbackCategory" },
  sensitive_word_category: { seed: "敏感词分类", key: "dictSensitiveWordCategory" },
};

const itemLabels: Record<string, Seed> = {
  "work_order_feedback_status\0CLOSED": { seed: "已关闭", key: "dictItemClosed" },
  "work_order_feedback_status\0RESOLVED": { seed: "已解决", key: "dictItemResolved" },
  "work_order_feedback_status\0PROCESSING": { seed: "处理中", key: "dictItemProcessing" },
  "work_order_feedback_status\0OPEN": { seed: "待处理", key: "dictItemOpen" },
  "work_order_feedback_priority\0URGENT": { seed: "紧急", key: "dictItemUrgent" },
  "work_order_feedback_priority\0HIGH": { seed: "高", key: "dictItemHigh" },
  "work_order_feedback_priority\0NORMAL": { seed: "普通", key: "dictItemNormal" },
  "work_order_feedback_priority\0LOW": { seed: "低", key: "dictItemLow" },
  "work_order_feedback_default\0IMAGE_REMARK": { seed: "工单反馈富文本图片", key: "dictItemImageRemark" },
  "work_order_feedback_default\0IMAGE_CATEGORY": { seed: "工单反馈", key: "dictItemImageCategory" },
  "work_order_feedback_category\0QUESTION": { seed: "业务咨询", key: "dictItemQuestion" },
  "work_order_feedback_category\0BUG": { seed: "问题反馈", key: "dictItemBug" },
  "work_order_feedback_category\0FEATURE": { seed: "功能建议", key: "dictItemFeature" },
  "work_order_feedback_category\0ACCOUNT": { seed: "账号与权限", key: "dictItemAccount" },
  "work_order_feedback_category\0OTHER": { seed: "其他", key: "dictItemOther" },
  "sensitive_word_category\0DEFAULT": { seed: "默认分类", key: "dictItemDefaultCategory" },
  "sensitive_word_category\0IMPORTED": { seed: "导入词", key: "dictItemImported" },
};

function overlay(entry: Seed | undefined, stored: string, t: Translate) {
  return entry && stored === entry.seed ? t(entry.key) : stored;
}

export function builtinDictionaryName(code: string, stored: string, t: Translate) {
  return overlay(dictionaryNames[code], stored, t);
}

export function builtinDictionaryItemLabel(code: string, value: string, stored: string, t: Translate) {
  return overlay(itemLabels[`${code}\0${value}`], stored, t);
}
