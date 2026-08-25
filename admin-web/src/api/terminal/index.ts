import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import type {
  ChangeTerminalStatusRequest,
  IssuedActivationVO,
  IssueActivationRequest,
  RotatedCredentialVO,
  TerminalPageVO,
  TerminalVO
} from './types';
import { newTerminalCommandKey, TERMINAL_ENDPOINTS, trustedTerminalPayload } from './contract';

export const listTerminals = (params: { storeId?: number; page: number; size: number }): AxiosPromise<TerminalPageVO> =>
  request({ url: TERMINAL_ENDPOINTS.terminals, method: 'get', params: trustedTerminalPayload(params) });

export const issueTerminalActivation = (data: IssueActivationRequest): AxiosPromise<IssuedActivationVO> =>
  request({ url: TERMINAL_ENDPOINTS.activations, method: 'post', data: trustedTerminalPayload(data) });

export const cancelTerminalActivation = (activationId: string): AxiosPromise<void> =>
  request({ url: `${TERMINAL_ENDPOINTS.activations}/${activationId}/cancel`, method: 'post' });

export const changeTerminalStatus = (deviceId: string, data: ChangeTerminalStatusRequest): AxiosPromise<TerminalVO> =>
  request({
    url: `${TERMINAL_ENDPOINTS.terminals}/${deviceId}/status`,
    method: 'put',
    data: trustedTerminalPayload(data)
  });

export const rotateTerminalCredential = (deviceId: string, idempotencyKey = newTerminalCommandKey()): AxiosPromise<RotatedCredentialVO> =>
  request({
    url: `${TERMINAL_ENDPOINTS.terminals}/${deviceId}/credentials/rotate`,
    method: 'post',
    headers: { 'Idempotency-Key': idempotencyKey }
  });
