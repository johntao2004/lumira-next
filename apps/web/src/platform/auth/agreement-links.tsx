"use client";
import {QueryFailure} from "../components/query-failure";
import {useState} from "react";
import {Button,Modal,Space} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api} from "../api/client";
import {usePreferences} from "../providers";
import {MarkdownContent} from "../components/markdown-content";
type Agreements={userAgreementMarkdown:string;privacyAgreementMarkdown:string};
export function AgreementLinks(){
 const {t}=usePreferences();const [selected,setSelected]=useState<keyof Agreements|null>(null);
 const query=useQuery({queryKey:["public-agreements"],queryFn:({signal})=>api<Agreements>("/public/agreements",{signal})});
 return <>{query.error&&<QueryFailure title={t("agreementLoadFailed")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}<Space wrap>
 {query.data?.userAgreementMarkdown.trim()&&<Button type="link" onClick={()=>setSelected("userAgreementMarkdown")}>{t("userAgreement")}</Button>}
 {query.data?.privacyAgreementMarkdown.trim()&&<Button type="link" onClick={()=>setSelected("privacyAgreementMarkdown")}>{t("privacyAgreement")}</Button>}
 </Space><Modal open={selected!==null} title={t(selected==="privacyAgreementMarkdown"?"privacyAgreement":"userAgreement")} onCancel={()=>setSelected(null)} footer={null} destroyOnHidden>
 <MarkdownContent>{selected?query.data?.[selected]??"":""}</MarkdownContent>
 </Modal></>;
}
