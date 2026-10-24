import { useId, useState } from 'react';
export function PrincipalCombobox({ value, suggestions, disabled, onChange }: {value:string; suggestions:string[]; disabled:boolean; onChange:(value:string,mode:string)=>void}) {
  const id=useId(),[open,setOpen]=useState(false),[active,setActive]=useState(0);
  const specials=[{label:'All principals',mode:'all',value:''},{label:'Mine',mode:'mine',value:'Mine'},{label:'Schedule',mode:'schedule',value:'Schedule'}];
  const options=[...specials,...suggestions.map(value=>({label:`User · ${value}`,mode:'text',value}))].filter(option=>!value||option.label.toLowerCase().includes(value.toLowerCase()));
  const choose=(index:number)=>{const item=options[index];if(item){onChange(item.value,item.mode);setOpen(false);}};
  return <div className="att-principal-combobox" onBlur={event=>{if(!event.currentTarget.contains(event.relatedTarget))setOpen(false);}}>
    <input id={`${id}-input`} role="combobox" aria-label="Principal" aria-controls={`${id}-options`} aria-autocomplete="list" aria-expanded={open} aria-activedescendant={open&&options[active]?`${id}-${active}`:undefined} autoComplete="off" disabled={disabled} value={value} placeholder="All principals" onFocus={()=>setOpen(true)} onChange={event=>{onChange(event.currentTarget.value,event.currentTarget.value?'text':'all');setOpen(true);setActive(0);}} onKeyDown={event=>{
      if(event.key==='Escape'){setOpen(false);return;}
      if(event.key==='Home'&&open){event.preventDefault();setActive(0);}
      if(event.key==='End'&&open){event.preventDefault();setActive(Math.max(0,options.length-1));}
      if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();setOpen(true);setActive(index=>options.length?(index+(event.key==='ArrowDown'?1:-1)+options.length)%options.length:0);}
      if(event.key==='Enter'&&open&&options.length){event.preventDefault();choose(active);}
    }} />
    <button type="button" aria-label="Clear Principal" disabled={disabled||!value} onClick={()=>{onChange('','all');document.getElementById(`${id}-input`)?.focus();setOpen(true);setActive(0);}}>×</button>
    {open&&<ul role="listbox" id={`${id}-options`} aria-label="Principal suggestions">{options.map((option,index)=><li role="option" id={`${id}-${index}`} aria-selected={index===active} key={`${option.mode}:${option.value}`} onMouseDown={event=>event.preventDefault()} onClick={()=>choose(index)}>{option.label}</li>)}{!options.length&&<li role="presentation">No suggestions. Keep typing to search on Server.</li>}</ul>}
  </div>;
}
