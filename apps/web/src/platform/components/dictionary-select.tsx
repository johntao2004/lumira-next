"use client";
import { ResourceSelect } from "./resource-select";

/** Stores a stable item UUID; only enabled dictionaries/items can be selected. */
export function DictionarySelect({code,...props}:{code:string;id?:string;value?:string|null;onChange?:(value:string|null)=>void;disabled?:boolean}) {
  return <ResourceSelect {...props} endpoint={`/dictionaries/${encodeURIComponent(code)}/items`} labelField="label" />;
}
