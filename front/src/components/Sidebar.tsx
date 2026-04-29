import { LayoutGrid, Database, Brain, BarChart3, HelpCircle, LifeBuoy, Plus, Cpu as Hub } from 'lucide-react';
import { ViewType } from '../App';

interface SidebarProps {
  activeView: ViewType;
  onViewChange: (view: ViewType) => void;
}

export default function Sidebar({ activeView, onViewChange }: SidebarProps) {
  const menuItems = [
    { id: 'workspace', label: 'AI Workspace', icon: LayoutGrid },
    { id: 'resources', label: 'Resource Library', icon: Database },
    { id: 'models', label: 'Model Library', icon: Brain },
    { id: 'analytics', label: 'Business Library', icon: BarChart3 },
  ] as const;

  return (
    <aside className="h-full w-64 fixed left-0 top-0 pt-16 z-40 bg-[#1e293b] text-slate-300 border-r border-slate-700 flex flex-col py-6 px-4 gap-4">
      <div className="mb-4">
        <div className="flex items-center gap-3 px-2 mb-1">
          <div className="w-8 h-8 rounded bg-indigo-500 flex items-center justify-center shadow-lg shadow-indigo-500/20">
            <Hub className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="text-white font-bold font-headline text-xs tracking-tight uppercase">Precision Observer</div>
            <div className="font-sans text-[9px] font-semibold text-slate-500 opacity-80">v1.0.0 PRO</div>
          </div>
        </div>
      </div>

      <button className="w-full py-2 bg-indigo-600 text-white font-headline font-bold rounded-md flex items-center justify-center gap-2 hover:bg-indigo-500 active:scale-95 transition-all mb-4 shadow-sm">
        <Plus className="w-4 h-4" />
        <span>New Analysis</span>
      </button>

      <nav className="flex-1 flex flex-col gap-0.5 px-1">
        {menuItems.map((item) => (
          <button
            key={item.id}
            onClick={() => onViewChange(item.id)}
            className={`flex items-center gap-3 px-3 py-2 rounded-md font-sans text-xs font-medium transition-all duration-200 group ${
              activeView === item.id 
                ? 'bg-slate-700/50 text-white' 
                : 'text-slate-400 hover:bg-slate-800 hover:text-white'
            }`}
          >
            <item.icon className={`w-4 h-4 ${activeView === item.id ? 'text-white' : 'text-slate-500 group-hover:text-slate-300'}`} />
            {item.label}
          </button>
        ))}
      </nav>

      <div className="border-t border-slate-700 pt-4 flex flex-col gap-0.5 px-1">
        <button className="flex items-center gap-3 px-3 py-2 text-slate-400 hover:bg-slate-800 hover:text-white rounded-md text-xs font-medium transition-all duration-200">
          <HelpCircle className="w-4 h-4" />
          Documentation
        </button>
        <button className="flex items-center gap-3 px-3 py-2 text-slate-400 hover:bg-slate-800 hover:text-white rounded-md text-xs font-medium transition-all duration-200">
          <LifeBuoy className="w-4 h-4" />
          Support
        </button>
        <div className="mt-4 p-2 bg-slate-800/50 rounded-lg flex items-center gap-3 border border-slate-700/50">
          <div className="w-8 h-8 bg-indigo-100 rounded-full flex items-center justify-center text-[10px] font-bold text-indigo-700 shadow-inner">JD</div>
          <div className="text-[10px]">
            <div className="font-bold text-white leading-tight">James Dalton</div>
            <div className="text-slate-500">Pro Tier</div>
          </div>
        </div>
      </div>
    </aside>
  );
}

