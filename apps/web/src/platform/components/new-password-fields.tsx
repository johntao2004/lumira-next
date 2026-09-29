"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {Input} from "antd";
import {usePreferences} from "../providers";
import {MAX_PASSWORD_BYTES, defaultPasswordLengthValid} from "../auth/password-contract";

/** Matching password rules for authenticated changes and anonymous recovery. */
export function NewPasswordFields(){
 const {t}=usePreferences();
 return <>
  <Form.Item name="newPassword" label={t("newPassword")} extra={t("passwordPolicy")} rules={[{required:true,message:t("passwordRequired")},{validator:(_,value)=>!value||defaultPasswordLengthValid(value)?Promise.resolve():Promise.reject(new Error(t("passwordPolicy")))}]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES}/></Form.Item>
  <Form.Item name="confirmation" label={t("confirmPassword")} dependencies={["newPassword"]} rules={[{required:true,message:t("passwordRequired")},({getFieldValue})=>({validator:(_,value)=>!value||value===getFieldValue("newPassword")?Promise.resolve():Promise.reject(new Error(t("passwordMismatch")))})]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES}/></Form.Item>
 </>;
}
