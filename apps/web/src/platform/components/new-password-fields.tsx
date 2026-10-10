"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Alert, Button, Input } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import {usePreferences} from "../providers";
import {DEFAULT_PASSWORD_RULES, MAX_PASSWORD_BYTES, passwordValidForRules, type PasswordRules} from "../auth/password-contract";
import type {MessageKey} from "../i18n/messages";

/** Shared password inputs; defaults retain the existing account-change and recovery behavior. */
export function NewPasswordFields({rules,passwordName="newPassword",confirmationName="confirmation",passwordLabel="newPassword"}:{rules?:PasswordRules;passwordName?:string;confirmationName?:string;passwordLabel?:MessageKey}){
 const {t}=usePreferences();
 const policyQuery=useQuery({queryKey:["password-policy"],enabled:rules===undefined,queryFn:({signal})=>api<{passwordRules:PasswordRules}>("/auth/registration/config",{signal})});
 const effectiveRules=rules??policyQuery.data?.passwordRules??DEFAULT_PASSWORD_RULES;
 const policyPending=rules===undefined&&policyQuery.isPending;
 const policyUnavailable=rules===undefined&&Boolean(policyQuery.error);
 const requiredTypes=[
  ...(effectiveRules.requireUppercase?[t("registrationPasswordUppercase")]:[]),
  ...(effectiveRules.requireLowercase?[t("registrationPasswordLowercase")]:[]),
  ...(effectiveRules.requireSpecialCharacter?[t("registrationPasswordSpecial")]:[]),
 ].join(", ")||t("registrationPasswordNoExtraTypes");
 const policyText=t("registrationPasswordPolicy",{
  minLength:effectiveRules.minLength,
  maxBytes:effectiveRules.maxBytes,
  requiredTypes,
  consecutiveRule:t(effectiveRules.allowConsecutiveCharacters?"registrationPasswordConsecutiveAllowed":"registrationPasswordConsecutiveBlocked"),
 });
 const policyMessage=rules||policyQuery.data?policyText:t("unavailable");
 return <>
  {policyUnavailable&&<Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={()=>void policyQuery.refetch()}>{t("retry")}</Button>} />}
  <Form.Item name={passwordName} label={t(passwordLabel)} extra={policyMessage} rules={[{required:true,message:t("passwordRequired")},{validator:(_,value)=>policyPending||policyUnavailable?Promise.reject(new Error(t("unavailable"))):!value||passwordValidForRules(value,effectiveRules)?Promise.resolve():Promise.reject(new Error(policyMessage))}]}><Input.Password autoComplete="new-password" disabled={policyPending||policyUnavailable} maxLength={Math.min(MAX_PASSWORD_BYTES,effectiveRules.maxBytes)}/></Form.Item>
  <Form.Item name={confirmationName} label={t("confirmPassword")} dependencies={[passwordName]} rules={[{required:true,message:t("passwordRequired")},({getFieldValue})=>({validator:(_,value)=>!value||value===getFieldValue(passwordName)?Promise.resolve():Promise.reject(new Error(t("passwordMismatch")))})]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES}/></Form.Item>
 </>;
}
