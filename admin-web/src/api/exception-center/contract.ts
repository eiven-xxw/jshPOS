export const EXCEPTION_CENTER_ENDPOINT = '/api/v1/operations/exceptions';
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;

export const exceptionCaseId = (value: string) => {
  if (!ULID.test(value)) throw new Error('异常案件标识无效');
  return value;
};

/** 禁止页面把 tenant、来源摘要、严重级别或 Owner 结果塞入写请求。 */
export const trustedExceptionPayload = <T extends Record<string, unknown>>(value: T): T => {
  const forbidden = ['tenantId', 'sourceSha256', 'severity', 'ownerResult', 'state'];
  if (forbidden.some((key) => Object.prototype.hasOwnProperty.call(value, key))) throw new Error('请求包含服务端权威字段');
  return value;
};
