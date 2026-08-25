/**
 * 高风险页面操作的稳定身份管理器。
 *
 * 同一对象/动作在服务端明确成功前始终复用原键；失败、超时和组件内重试
 * 不会生成第二个业务命令。该工具只管理页面交互身份，不替代服务端幂等校验。
 */
export const useStableOperationIdentity = (factory: () => string) => {
  const identities = new Map<string, string>();

  const get = (operation: string): string => {
    const existing = identities.get(operation);
    if (existing) return existing;
    const created = factory();
    identities.set(operation, created);
    return created;
  };

  /** 只有服务端已明确确认成功后才允许释放原操作身份。 */
  const complete = (operation: string): void => {
    identities.delete(operation);
  };

  const peek = (operation: string): string | undefined => identities.get(operation);

  return { get, complete, peek };
};
