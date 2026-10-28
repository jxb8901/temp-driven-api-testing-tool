import { useId, useState } from 'react';

export type IdentityMode = 'all' | 'mine' | 'text';
export interface IdentityFilter { mode: IdentityMode; value: string; exact: boolean }

export function IdentityCombobox({ label, value, mode, suggestions, disabled, onChange }: {
  label: string; value: string; mode: IdentityMode; suggestions: string[]; disabled: boolean;
  onChange: (filter: IdentityFilter) => void;
}) {
  const id=useId(),[open,setOpen]=useState(false),[active,setActive]=useState(0);
  const options=[{label:`All ${label.toLowerCase()}s`,mode:'all' as const,value:'',exact:false},{label:'Mine',mode:'mine' as const,value:'',exact:false},
    ...suggestions.map(item=>({label:item,mode:'text' as const,value:item,exact:true}))]
    .filter(option=>!value||option.label.toLowerCase().includes(value.toLowerCase()));
  const display=mode==='mine'?'Mine':mode==='text'?value:'';
  const choose=(index:number)=>{const item=options[index];if(item){onChange({mode:item.mode,value:item.value,exact:item.exact});setOpen(false);}};
  return <div className="att-principal-combobox" onBlur={event=>{if(!event.currentTarget.contains(event.relatedTarget))setOpen(false);}}>
    <input id={`${id}-input`} role="combobox" aria-label={label} aria-controls={`${id}-options`} aria-autocomplete="list" aria-expanded={open} aria-activedescendant={open&&options[active]?`${id}-${active}`:undefined} autoComplete="off" disabled={disabled} value={display} placeholder={`All ${label.toLowerCase()}s`} onFocus={()=>setOpen(true)} onChange={event=>{onChange({mode:event.currentTarget.value?'text':'all',value:event.currentTarget.value,exact:false});setOpen(true);setActive(0);}} onKeyDown={event=>{
      if(event.key==='Escape'){setOpen(false);return;}
      if(event.key==='Home'&&open){event.preventDefault();setActive(0);}
      if(event.key==='End'&&open){event.preventDefault();setActive(Math.max(0,options.length-1));}
      if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();setOpen(true);setActive(index=>options.length?(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length:0);}
      if(event.key==='Enter'&&open&&options.length){event.preventDefault();choose(active);}
    }} />
    <button type="button" aria-label={`Clear ${label}`} disabled={disabled||(!value&&mode==='all')} onClick={()=>{onChange({mode:'all',value:'',exact:false});document.getElementById(`${id}-input`)?.focus();setOpen(true);setActive(0);}}>×</button>
    {open&&<ul role="listbox" id={`${id}-options`} aria-label={`${label} suggestions`}>{options.map((option,index)=><li role="option" id={`${id}-${index}`} aria-selected={index===active} key={`${option.mode}:${option.value}`} onMouseDown={event=>event.preventDefault()} onClick={()=>choose(index)}>{option.label}</li>)}{!options.length&&<li role="presentation">No suggestions. Keep typing to search on Server.</li>}</ul>}
  </div>;
}
