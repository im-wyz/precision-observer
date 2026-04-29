import { Bell, Settings } from 'lucide-react';
import { motion } from 'motion/react';
import { ViewType } from '../App';

interface HeaderProps {
  activeView: ViewType;
  onViewChange: (view: ViewType) => void;
}

export default function Header({ activeView, onViewChange }: HeaderProps) {
  const navItems: { label: string; id: ViewType }[] = [
    { label: 'Workspace', id: 'workspace' },
    { label: 'Resources', id: 'resources' },
    { label: 'Models', id: 'models' },
    { label: 'Analytics', id: 'analytics' },
  ];

  return (
    <header className="fixed top-0 w-full z-50 bg-white flex items-center justify-between px-6 py-2 border-b border-slate-200 shadow-sm shrink-0">
      <div className="flex items-center gap-8">
        <div className="flex items-center gap-2">
          <span 
            className="text-lg font-bold tracking-tight text-slate-800 font-headline uppercase cursor-pointer"
            onClick={() => onViewChange('workspace')}
          >
            Observer
          </span>
          <span className="px-2 py-0.5 bg-slate-100 text-slate-500 text-[10px] font-bold rounded uppercase">V1.0.0</span>
        </div>
        <nav className="hidden md:flex gap-6 font-headline tracking-tight text-sm font-medium">
          {navItems.map((item) => (
            <button
              key={item.id}
              onClick={() => onViewChange(item.id)}
              className={`pb-1 transition-all duration-200 active:scale-95 relative ${
                activeView === item.id ? 'text-indigo-600' : 'text-slate-500 hover:text-indigo-500'
              }`}
            >
              {item.label}
              {activeView === item.id && (
                <motion.div
                  layoutId="header-nav-indicator"
                  className="absolute -bottom-[1px] left-0 right-0 h-0.5 bg-indigo-600"
                />
              )}
            </button>
          ))}
        </nav>
      </div>
      
      <div className="flex items-center gap-4">
        <div className="relative hidden sm:block">
          <input 
            type="text" 
            placeholder="Search commands..." 
            className="bg-slate-100 border-none rounded-md px-4 py-1.5 text-xs w-64 focus:ring-2 focus:ring-indigo-500 transition-all outline-none" 
          />
          <span className="absolute right-3 top-2 text-slate-400 text-[10px] font-mono">⌘K</span>
        </div>
        <button className="text-slate-400 hover:text-indigo-600 transition-all duration-200 active:scale-95">
          <Bell className="w-5 h-5" />
        </button>
        <button className="text-slate-400 hover:text-indigo-600 transition-all duration-200 active:scale-95">
          <Settings className="w-5 h-5" />
        </button>
      </div>
    </header>
  );
}
