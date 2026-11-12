export type Role = {id:string;code:string;name:string;enabled:boolean;version:number;dataScope:"SELF"|"ORGANIZATION"|"SUBTREE"|"ALL";fileQuotaBytesLimit:number|null;fileQuotaFilesLimit:number|null;authorities:string[];defaultRegistrationRole:boolean};
export type UserRoles = {version:number;dataScope:"SELF"|"ORGANIZATION"|"SUBTREE"|"ALL";roleIds:string[]};
