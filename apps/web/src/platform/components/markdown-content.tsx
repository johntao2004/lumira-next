"use client";
import Markdown from "react-markdown";
/** Common text renderer. Raw HTML is excluded; links use react-markdown's safe URL transform. */
export function MarkdownContent({children}:{children:string}){
 return <div className="markdown-content" style={{overflowWrap:"anywhere"}}><Markdown skipHtml components={{img:({src,alt})=><img src={src} alt={alt??""} loading="lazy" style={{maxWidth:"100%",height:"auto"}}/>,a:({children,href})=><a href={href} target="_blank" rel="noopener noreferrer">{children}</a>,pre:({children})=><pre style={{overflowX:"auto"}}>{children}</pre>}}>{children}</Markdown></div>;
}
