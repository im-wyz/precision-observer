import { Search, Calendar, Target, Satellite, Sliders, Download, MoreVertical, Layers, Cpu, Sparkles, LineChart, PlusCircle, UploadCloud, Box } from 'lucide-react';
import { motion } from 'motion/react';

const assets = [
  {
    id: 'S2B_MSIL1C',
    name: 'Sentinel-2B L1C',
    code: 'S2B_MSIL1C_20231012',
    status: 'OPERATIONAL',
    resolution: '10.0m GSD',
    sensor: 'MSI Optical',
    date: 'Oct 12, 2023',
    cloud: '2.4%',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuA5r9WgBTM9EMHEyWAUUIvtd-l3PqiusK-Zjk8cuLPVrKRbs9fIvVAhg38-GCDJol6Z7P4jOXKCVPNS1SXDu5sxwxN5GSnc81C3fSNpHy1ljbOh7MNztxuwIBxWDt2hgJXXLNHzOWtgWLvtKH-LGjbEDwR2fhMIzw88nilQQmEY0QZvTHXbsbQp-NLPcYdzBGc_1wGNfGmnTNzkwyvCAW_TwVnyBNHXelPYS6ifSunbFOoNSOXuAj4tXSEMY_iRaG-CCDOX6V6_d48',
    color: 'secondary'
  },
  {
    id: 'LC09_L1TP',
    name: 'Landsat-9 OLI-2',
    code: 'LC09_L1TP_123045',
    status: 'ARCHIVED',
    resolution: '30.0m GSD',
    sensor: 'OLI TIRS',
    date: 'Sep 28, 2023',
    cloud: '12.1%',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuALOrOZBrRwEjtGAJaM2EPXzBnYu0YngiwHkBhht0rUG--W2_pMIqbY6sYZXOgrMWVmD15Dd3NwJNcqn3yetEHE_mP_FMaU4Y7060YKkXU7xJ7RxQpUZVP8GJQiGeZWeJo03eCrkCJCYiKLa7JbFd3tqFhUxqMCpMwQbWpNh53zcH0GKhINpBliaexAk6afbOOao_BCpiqKtw0uNOSJmuQoddaymH4Aq_PdZdf4AjX-6mI98Odv9tppEO4yX5heXJdq1JNU-9DYKHg',
    color: 'primary'
  },
  {
    id: 'WV03_PANC',
    name: 'Maxar WorldView-3',
    code: 'WV03_PANC_20230514',
    status: 'PROCESSING',
    resolution: '0.31m GSD',
    sensor: 'Panchromatic',
    date: 'May 14, 2023',
    cloud: '0.0%',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuBF-alauHMdUz-Kqex5OqkBBpKXQ1N3D8ZQLQ8p9xdkXDUih3awogFZKyW3TzAzo7cYPZUBIxYwmFpZ1-IUf98uGI0BV49zinSE4uaDIqDLvKSnnkEr3Ss4cEcDnMySla0Pc2boMx-rpxXliRiZfMogIOhc74gqoKORmGfgwST8b7utLUKHfWpZVOn8gSzN1TAfQ6RZRoM9Sv8vYrZjGG4HRVXkxUGSNe3cECocizBM-CKINkiccOTiBR6Lh8mwqFGf_Q2iL6mIhgk',
    color: 'tertiary'
  },
  {
    id: 'PS_SD_3B',
    name: 'PlanetScope SuperDove',
    code: 'PS_SD_3B_20230819',
    status: 'OPERATIONAL',
    resolution: '3.0m GSD',
    sensor: '8-Band Optical',
    date: 'Aug 19, 2023',
    cloud: '5.1%',
    image: 'https://lh3.googleusercontent.com/aida-public/AB6AXuC0fRgw4t9dN7Sy9cOfd8GfxFnxJnTavFsNsFMFjXEWaG73GOsZL6HVMY-k_u6kGG_CXw5WAMgAgOWKhtMwHQ8jucdtWLJHdKk9TVaMqgR1ahmiTiYdtNcLh6JYP6Yb2iQeEwprQh7gK1gDZqSLU7o9Cq8zQjYWhbuWGG9Fcc7HCpJ2CB4R9JaKZhRja64Z-PpAcASdGEJuq9Uzbi9HtyPUtn2BLwfQvx6O99cnOAOH7E9SqkLiY1TDDzcFd0-TWe66NbnQfXzIch4',
    color: 'secondary'
  }
];

export default function ResourceLibrary() {
  return (
    <div className="h-full flex flex-col bg-[#f8f9fa] p-8 overflow-hidden">
      {/* Header Section */}
      <header className="mb-8">
        <div className="flex justify-between items-end mb-6">
          <div>
            <h1 className="text-3xl font-headline font-bold text-slate-800 tracking-tight mb-1">Resource Library</h1>
            <p className="text-slate-500 font-sans text-[10px] font-bold uppercase tracking-widest opacity-80">Geospatial Data Asset Management</p>
          </div>
          <div className="hidden xl:flex gap-4">
            <div className="px-4 py-2 bg-white rounded-lg flex items-center gap-3 border border-slate-200 shadow-sm h-fit">
              <Cpu className="w-5 h-5 text-indigo-500" />
              <div>
                <div className="text-[9px] text-slate-400 uppercase font-bold leading-none tracking-wider">Storage Used</div>
                <div className="text-sm font-headline font-bold text-slate-800">1.2 TB / 5.0 TB</div>
              </div>
            </div>
          </div>
        </div>

        {/* Filter Bar */}
        <div className="grid grid-cols-12 gap-3">
          <div className="col-span-4 relative group">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 w-4 h-4 group-focus-within:text-indigo-500 transition-colors" />
            <input 
              className="w-full bg-white border border-slate-200 focus:ring-2 focus:ring-indigo-500 rounded-md pl-9 pr-4 py-2 text-xs font-sans placeholder:text-slate-400 transition-all outline-none shadow-sm" 
              placeholder="Search assets (e.g., Sentinel, Landsat)..." 
              type="text"
            />
          </div>
          <div className="col-span-2">
            <button className="w-full bg-white hover:bg-slate-50 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-pointer transition-colors group shadow-sm">
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">Time Range</span>
              <Calendar className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2">
            <button className="w-full bg-white hover:bg-slate-50 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-pointer transition-colors group shadow-sm">
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">BBox Search</span>
              <Target className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2">
            <button className="w-full bg-white hover:bg-slate-50 border border-slate-200 rounded-md px-3 py-2 flex items-center justify-between cursor-pointer transition-colors group shadow-sm">
              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-600">Source</span>
              <Satellite className="w-3.5 h-3.5 text-indigo-500" />
            </button>
          </div>
          <div className="col-span-2 flex gap-2">
            <button className="flex-1 bg-white border border-slate-200 rounded-md flex items-center justify-center hover:bg-slate-50 hover:text-indigo-600 transition-all shadow-sm">
              <Sliders className="w-4 h-4" />
            </button>
            <button className="flex-1 bg-white border border-slate-200 rounded-md flex items-center justify-center hover:bg-slate-50 hover:text-indigo-600 transition-all shadow-sm">
              <Download className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* Assets Grid */}
      <div className="flex-1 overflow-y-auto custom-scrollbar pr-2">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {assets.map((asset, index) => (
            <motion.div 
              key={asset.id}
              initial={{ opacity: 0, scale: 0.98 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: index * 0.05 }}
              className="group relative flex flex-col bg-white rounded-xl overflow-hidden shadow-sm hover:shadow-md border border-slate-200 transition-all duration-300 hover:translate-y-[-2px]"
            >
              <div className="aspect-[16/10] overflow-hidden relative">
                <img 
                  className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-700 opacity-90 group-hover:opacity-100" 
                  src={asset.image} 
                  referrerPolicy="no-referrer"
                />
                <div className={`absolute top-2 left-2 px-2 py-0.5 rounded text-[9px] font-bold tracking-wider flex items-center gap-1.5 border backdrop-blur-md
                  ${asset.status === 'OPERATIONAL' ? 'bg-emerald-50/80 text-emerald-600 border-emerald-200' : 
                    asset.status === 'ARCHIVED' ? 'bg-indigo-50/80 text-indigo-600 border-indigo-200' : 
                    'bg-amber-50/80 text-amber-600 border-amber-200'}`}>
                  <div className={`w-1.5 h-1.5 rounded-full ${asset.status === 'OPERATIONAL' ? 'bg-emerald-500' : asset.status === 'ARCHIVED' ? 'bg-indigo-500' : 'bg-amber-500'} animate-pulse`} />
                  {asset.status}
                </div>
              </div>
              <div className="p-4 flex flex-col gap-3">
                <div className="flex justify-between items-start">
                  <div>
                    <h3 className="font-headline font-bold text-slate-800 text-sm">{asset.name}</h3>
                    <p className="text-[10px] font-mono text-slate-400 uppercase tracking-widest mt-0.5 opacity-80">{asset.code}</p>
                  </div>
                  <button className="text-slate-300 hover:text-indigo-600 transition-colors p-1"><MoreVertical className="w-4 h-4" /></button>
                </div>
                <div className="grid grid-cols-2 gap-y-2 border-t border-slate-50 pt-3">
                  <div>
                    <div className="text-[8px] uppercase tracking-wider text-slate-400 font-bold">Resolution</div>
                    <div className="text-[11px] font-bold text-slate-700">{asset.resolution}</div>
                  </div>
                  <div>
                    <div className="text-[8px] uppercase tracking-wider text-slate-400 font-bold">Sensor</div>
                    <div className="text-[11px] font-bold text-slate-700">{asset.sensor}</div>
                  </div>
                </div>
                <div className="flex items-center gap-2 pt-1">
                  <div className="text-[9px] font-bold text-indigo-600 bg-indigo-50 px-1.5 py-0.5 rounded">{asset.date}</div>
                  <div className="text-[9px] font-bold text-slate-500 bg-slate-100 px-1.5 py-0.5 rounded">{asset.cloud} Cloud</div>
                  <div className="ml-auto flex gap-1 items-center">
                    <Layers className="w-3.5 h-3.5 text-slate-300" />
                    <Sparkles className="w-3.5 h-3.5 text-slate-300" />
                  </div>
                </div>
              </div>
            </motion.div>
          ))}

          {/* Add Placeholder */}
          <motion.div 
            className="flex flex-col items-center justify-center border-2 border-dashed border-slate-200 rounded-xl p-8 hover:border-indigo-400 transition-all group cursor-pointer bg-slate-50/50"
          >
            <div className="w-12 h-12 rounded-full bg-white flex items-center justify-center mb-3 group-hover:scale-110 transition-transform shadow-sm border border-slate-200">
              <PlusCircle className="w-6 h-6 text-indigo-500" />
            </div>
            <div className="text-xs font-bold text-slate-600">Import Asset</div>
            <div className="text-[9px] font-bold text-slate-400 uppercase tracking-widest mt-1">STAC, COG, GeoTIFF</div>
          </motion.div>
        </div>
      </div>

      {/* Floating Action Button */}
      <button className="fixed bottom-6 right-6 w-12 h-12 bg-indigo-600 text-white rounded-full shadow-lg flex items-center justify-center hover:scale-110 active:scale-95 transition-transform z-50 group">
        <UploadCloud className="w-6 h-6" />
      </button>

      {/* Floating Preview Panel */}
      <div className="fixed top-24 right-8 w-72 glass-panel rounded-xl p-6 border border-outline/10 hidden xl:block shadow-2xl">
        <h4 className="text-[10px] font-headline font-bold text-primary tracking-widest uppercase mb-4">Spatial Selection</h4>
        <div className="aspect-square bg-zinc-950 rounded-lg mb-4 overflow-hidden relative border border-outline/20">
          <img 
            className="w-full h-full object-cover opacity-60 mix-blend-screen" 
            src="https://lh3.googleusercontent.com/aida-public/AB6AXuB3PDvUjBhZVtuXsTsWppD-S1378PiOVqyAhZ7WknCOyXEn1xZUySFtLtZAxmk0vd0RbfbkHygxLHjimGmVpJu8QV1gxG2iMUl_ptxO8htu_Co2AB_4SvsLWcxgEVQD1e2ucKdkGGivBb37x_7VGwUNjnO3xWXHwGf1MwjofHE0DFd2s0tYooBqPyPOCroIHic6zRe0kix0lTpgf8LJoHDnNjWDA6pokLqV2ROPZlpIeTalBgjA4xLdCZiE8EwUxnOyKVzoHud699M"
            referrerPolicy="no-referrer"
          />
          <div className="absolute inset-0 bg-primary/5" />
        </div>
        <div className="space-y-4">
          <div>
            <div className="text-[9px] text-on-surface-variant font-bold uppercase tracking-widest opacity-50">Bounding Box</div>
            <div className="text-[11px] font-mono text-secondary mt-1">40.7128° N, 74.0060° W</div>
            <div className="text-[11px] font-mono text-secondary">40.7851° N, 73.9683° W</div>
          </div>
          <div>
            <div className="text-[9px] text-on-surface-variant font-bold uppercase tracking-widest opacity-50">Total Assets</div>
            <div className="text-xl font-headline font-bold text-on-surface">1,248</div>
          </div>
          <button className="w-full py-2 bg-surface-high border border-outline/30 text-on-surface text-[10px] font-bold uppercase tracking-widest rounded hover:bg-primary hover:text-background transition-all shadow-lg active:scale-95">
            Export Selected
          </button>
        </div>
      </div>
    </div>
  );
}

