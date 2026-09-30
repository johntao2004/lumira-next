"use client";
import { ResourceSelect } from "./resource-select";

type CurrentDictionarySelection={itemId:string;label:string;enabled:boolean};
/** Stores a stable item UUID; only enabled dictionaries/items can be selected. */
export function DictionarySelect({code,currentSelection,...props}:{code:string;currentSelection?:CurrentDictionarySelection;id?:string;value?:string|null;onChange?:(value:string|null)=>void;disabled?:boolean}) {
  return <ResourceSelect {...props} endpoint={`/dictionaries/${encodeURIComponent(code)}/items`} labelField="label" currentSelection={currentSelection} />;
}
