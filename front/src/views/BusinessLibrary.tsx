import { Leaf, Gavel, Droplets, TreeDeciduous, PlayCircle, PauseCircle, XCircle, FileText, CheckCircle2, FileDown } from 'lucide-react';
import { motion } from 'motion/react';

const scenarios = [
  { id: 1, title: 'Crop Growth Monitoring', desc: 'NDVI analysis and predictive yield mapping for large-scale agricultural sectors.', icon: Leaf },
  { id: 2, title: 'Illegal Construction Audit', desc: 'Temporal change detection for urban enforcement and land use compliance.', icon: Gavel },
  { id: 3, title: 'Flood Risk Assessment', desc: 'Hydrological modeling paired with high-res digital elevation for disaster prep.', icon: Droplets },
  { id: 4, title: 'Forest Carbon Sequestration', desc: 'Biomass density calculations for ESG reporting and carbon credit markets.', icon: TreeDeciduous },
];

const pipelineTasks = [
  { id: 1, title: 'Urban Expansion Analysis [SHA-2023]', status: 'Processing', time: 'Est. 4m remaining', progress: 72, color: 'primary' },
  { id: 2, title: 'Sentinel-2 Cloud Removal Batch', status: 'Pending', time: 'Pending resources', progress: 15, color: 'tertiary' },
  { id: 3, title: 'Infrastructure Violation Scan - Sector 7', status: 'Processing', time: 'Processing TIFF [34/112]', progress: 45, color: 'primary' },
];

export default function BusinessLibrary() {
  return (
    <div className="h-full flex flex-col bg-[#f8f9fa] p-8 overflow-y-auto custom-scrollbar">
      {/* Header Section */}
      <header className="flex flex-col gap-1 mb-10">
        <div className="flex items-center gap-2">
          <span className="h-[1px] w-6 bg-indigo-500"></span>
          <span className="text-indigo-600 font-headline text-[10px] uppercase tracking-[0.2em] font-bold">Strategic Operations</span>
        </div>
        <h1 className="font-headline text-3xl font-bold tracking-tight text-slate-800">Business Library</h1>
        <p className="text-slate-500 max-w-2xl font-sans text-xs leading-relaxed opacity-80">Scene-based workflow management and executive-level geospatial intelligence insights.</p>
      </header>

      {/* Templates */}
      <section className="mb-12">
        <div className="flex items-end justify-between mb-4 border-b border-slate-200 pb-2">
          <h3 className="font-headline text-lg font-bold text-slate-700">Business Scenario Templates</h3>
          <button className="text-indigo-600 text-[10px] font-bold uppercase tracking-widest hover:underline">View All</button>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {scenarios.map((s, idx) => (
            <motion.div 
              key={idx}
              initial={{ opacity: 0, y: 5 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: idx * 0.05 }}
              className="group bg-white p-5 rounded-xl transition-all border border-slate-200 hover:border-indigo-300 shadow-sm hover:translate-y-[-2px]"
            >
              <div className="flex justify-between items-start mb-3">
                <div className="w-8 h-8 rounded bg-slate-50 flex items-center justify-center border border-slate-100 shadow-sm">
                  <s.icon className="w-5 h-5 text-indigo-500" />
                </div>
              </div>
              <h4 className="font-headline text-base font-bold mb-1 text-slate-800 group-hover:text-indigo-600 transition-colors">{s.title}</h4>
              <p className="text-[10px] text-slate-500 mb-4 line-clamp-2 leading-relaxed h-8">{s.desc}</p>
              <button className="w-full py-1.5 bg-slate-50 border border-slate-200 text-indigo-600 text-[10px] uppercase tracking-widest font-bold rounded-md hover:bg-indigo-600 hover:text-white transition-all shadow-xs">Launch Workflow</button>
            </motion.div>
          ))}
        </div>
      </section>

      {/* Active Pipeline Table-like View */}
      <section className="mb-12">
        <h3 className="font-headline text-lg font-bold text-slate-700 mb-4 flex items-center gap-2">
          <PlayCircle className="w-4 h-4 text-emerald-500" />
          Live Pipeline Activity
        </h3>
        <div className="bg-white border border-slate-200 rounded-xl shadow-sm overflow-hidden">
          <div className="grid grid-cols-12 gap-4 px-6 py-3 bg-slate-50 border-b border-slate-200 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <div className="col-span-1">Status</div>
            <div className="col-span-5">Analysis Pipeline</div>
            <div className="col-span-4">Progress</div>
            <div className="col-span-2 text-right">Actions</div>
          </div>
          <div className="divide-y divide-slate-100">
            {pipelineTasks.map((t, idx) => (
              <div key={idx} className="grid grid-cols-12 gap-4 px-6 py-4 items-center hover:bg-slate-50 transition-colors group">
                <div className="col-span-1 flex justify-center">
                  <div className={`w-2 h-2 rounded-full ${t.status === 'Processing' ? 'bg-emerald-500 animate-pulse' : 'bg-amber-500'}`}></div>
                </div>
                <div className="col-span-5">
                  <div className="text-xs font-bold text-slate-800 group-hover:text-indigo-600">{t.title}</div>
                  <div className="text-[10px] text-slate-400 font-mono mt-0.5">{t.time}</div>
                </div>
                <div className="col-span-4 flex items-center gap-3">
                  <div className="flex-1 bg-slate-100 h-1 rounded-full overflow-hidden border border-slate-200 shadow-inner">
                    <motion.div 
                      initial={{ width: 0 }} 
                      animate={{ width: `${t.progress}%` }} 
                      className={`h-full rounded-full ${t.color === 'primary' ? 'bg-indigo-500' : 'bg-amber-500'}`} 
                    />
                  </div>
                  <span className="text-[10px] font-bold text-slate-500 w-8">{t.progress}%</span>
                </div>
                <div className="col-span-2 flex justify-end gap-2 text-slate-300">
                  <PauseCircle className="w-4 h-4 hover:text-indigo-600 cursor-pointer" />
                  <XCircle className="w-4 h-4 hover:text-rose-500 cursor-pointer" />
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Reports and Charts */}
      <section className="grid grid-cols-1 lg:grid-cols-3 gap-6 pb-10">
        <div className="lg:col-span-2 bg-white rounded-xl p-8 border border-slate-200 flex flex-col items-center justify-center relative min-h-[350px] shadow-sm">
          <div className="absolute top-6 left-6 w-full pr-12 flex justify-between items-start">
            <div>
              <h3 className="font-headline text-lg font-bold text-slate-800">Land Use Metric Analysis</h3>
              <p className="text-[9px] text-slate-400 mt-1 font-sans uppercase tracking-widest font-black opacity-60">Yangtze Delta • Q3 2023</p>
            </div>
            <div className="flex gap-3">
              <div className="flex items-center gap-1">
                <div className="w-2 h-2 bg-indigo-500 rounded-xs"></div>
                <span className="text-[9px] text-slate-500 uppercase font-black tracking-tight">Forest</span>
              </div>
              <div className="flex items-center gap-1">
                <div className="w-2 h-2 bg-emerald-500 rounded-xs"></div>
                <span className="text-[9px] text-slate-500 uppercase font-black tracking-tight">Urban</span>
              </div>
            </div>
          </div>

          <div className="relative w-48 h-48 mt-8">
            <svg className="absolute inset-0 w-full h-full -rotate-90 drop-shadow-lg" viewBox="0 0 100 100">
              <circle className="text-indigo-500" cx="50" cy="50" fill="transparent" r="40" stroke="currentColor" strokeDasharray="251" strokeDashoffset="60" strokeWidth="12" strokeLinecap="round"></circle>
              <circle className="text-emerald-500" cx="50" cy="50" fill="transparent" r="40" stroke="currentColor" strokeDasharray="251" strokeDashoffset="180" strokeWidth="12" strokeLinecap="round"></circle>
              <circle className="text-slate-100" cx="50" cy="50" fill="transparent" r="40" stroke="currentColor" strokeDasharray="251" strokeDashoffset="220" strokeWidth="12" strokeLinecap="round"></circle>
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="font-headline text-3xl font-bold text-slate-800 leading-none">1.2M</span>
              <span className="text-[7px] font-sans uppercase tracking-[0.3em] text-slate-400 font-black mt-1">Hectares</span>
            </div>
          </div>

          <div className="absolute bottom-6 right-6">
            <button className="text-indigo-600 text-[10px] font-bold uppercase tracking-widest flex items-center gap-1 hover:bg-indigo-50 px-2 py-1 rounded">
              Detailed breakdown
            </button>
          </div>
        </div>

        <div className="bg-[#1e293b] text-white rounded-xl p-8 border border-slate-700 flex flex-col justify-between shadow-xl relative overflow-hidden group">
          <div className="space-y-6 relative z-10">
            <div className="w-12 h-12 bg-indigo-500 rounded-lg flex items-center justify-center shadow-lg shadow-indigo-500/20">
              <FileDown className="w-6 h-6 text-white" />
            </div>
            <div>
              <h3 className="font-headline text-xl font-bold text-white">Executive Analytics</h3>
              <p className="text-xs text-slate-400 font-sans mt-2 leading-relaxed">Generated report includes hi-res map exports, delta comparisons, and AI transition vectors.</p>
            </div>
            <ul className="space-y-3">
              <li className="flex items-center gap-3 text-[11px]">
                <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                <span className="font-medium text-slate-300">Sentinel-2 Multispectral Change</span>
              </li>
              <li className="flex items-center gap-3 text-[11px]">
                <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                <span className="font-medium text-slate-300">DSM Structural Delta Mapping</span>
              </li>
            </ul>
          </div>
          <button className="mt-8 w-full py-3 bg-indigo-600 text-white font-headline font-bold rounded-lg shadow-lg shadow-indigo-900/20 hover:bg-indigo-500 transition-all flex items-center justify-center gap-2 active:scale-95">
            Generate Report
          </button>
        </div>
      </section>

      {/* Decorative Background Map Detail */}
      <div className="fixed bottom-0 right-0 w-1/3 h-1/2 -z-10 opacity-10 pointer-events-none mix-blend-screen overflow-hidden">
        <div 
          className="w-full h-full bg-cover bg-center grayscale contrast-150 brightness-50"
          style={{ backgroundImage: "url('https://lh3.googleusercontent.com/aida-public/AB6AXuACuMNp8Nr47fI8M14SWMQttH9pHTvzb6hWOvTsqs9UYEum52Q5VFIcuYXWbwdqOeaNbBA0q5wLRGJcd0Rm2tYDScp6AvQ-_qh2OItSyNK8rOYkOQ5cbfU_lR7sZLOwEZhyqHQIcRa5tTHrcivXbFWUbC-qNFCdDdIgksSAK6EbjkHpww8s4OG3KE6BkqyPtnz8o3krIg0ZESd9GppP1Ly5ZHkUfLyoeDL017v6-hrmbdborF1b14tMZwY0q8T5sWB9zjnvPj05Iz8')" }}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-background to-transparent"></div>
        <div className="absolute inset-0 bg-gradient-to-l from-background to-transparent"></div>
      </div>
    </div>
  );
}
