/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agent.dbgeng.rmi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.*;

import java.util.*;

import org.junit.Test;

import generic.Unique;
import ghidra.app.plugin.core.debug.service.rmi.trace.RemoteMethod;
import ghidra.app.plugin.core.debug.service.rmi.trace.ValueDecoder;
import ghidra.app.plugin.core.debug.utils.ManagedDomainObject;
import ghidra.dbg.testutil.DummyProc;
import ghidra.dbg.util.PathPattern;
import ghidra.dbg.util.PathPredicates;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.RegisterValue;
import ghidra.trace.database.ToyDBTraceBuilder;
import ghidra.trace.model.Lifespan;
import ghidra.trace.model.Trace;
import ghidra.trace.model.breakpoint.TraceBreakpointKind;
import ghidra.trace.model.memory.TraceMemoryRegion;
import ghidra.trace.model.memory.TraceMemorySpace;
import ghidra.trace.model.modules.TraceModule;
import ghidra.trace.model.target.TraceObject;
import ghidra.trace.model.target.TraceObjectValue;

public class DbgEngMethodsTest extends AbstractDbgEngTraceRmiTest {

	@Test
	public void testEvaluate() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			RemoteMethod evaluate = conn.getMethod("evaluate");
			assertEquals("11",
				evaluate.invoke(Map.of("expr", "3+4*2")));
		}
	}

	@Test
	public void testExecuteCapture() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			RemoteMethod execute = conn.getMethod("execute");
			assertEquals(false,
				execute.parameters().get("to_string").defaultValue().get(ValueDecoder.DEFAULT));
			assertEquals("11\n",
				execute.invoke(Map.of("cmd", "print(3+4*2)", "to_string", true)));
		}
	}

	@Test
	public void testExecute() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			conn.execute("ghidra_trace_kill()");
		}
		try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
			// Just confirm it's present
		}
	}

	@Test
	public void testRefreshAvailable() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, null);
			txCreate(conn, "Available");

			RemoteMethod refreshAvailable = conn.getMethod("refresh_available");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject available = Objects.requireNonNull(tb.objAny("Available"));

				refreshAvailable.invoke(Map.of("node", available));

				// Would be nice to control / validate the specifics
				List<TraceObject> list = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0), PathPredicates.parse("Available[]"))
						.map(p -> p.getDestination(null))
						.toList();
				assertThat(list.size(), greaterThan(2));
			}
		}
	}

	@Test
	public void testRefreshBreakpoints() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod refreshBreakpoints = conn.getMethod("refresh_breakpoints");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				conn.execute("dbg = util.get_debugger()");
				conn.execute("pc = dbg.reg.get_pc()");
				conn.execute("dbg.bp(expr=pc)");
				conn.execute("dbg.ba(expr=pc+4)");
				txPut(conn, "breakpoints");
				TraceObject breakpoints = Objects.requireNonNull(tb.objAny("Processes[].Breakpoints"));
				refreshBreakpoints.invoke(Map.of("node", breakpoints));

				List<TraceObjectValue> procBreakLocVals = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0),
							PathPredicates.parse("Processes[].Breakpoints[]"))
						.map(p -> p.getLastEntry())
						.toList();
				assertEquals(2, procBreakLocVals.size());
				AddressRange rangeMain =
					procBreakLocVals.get(0).getChild().getValue(0, "_range").castValue();
				Address main = rangeMain.getMinAddress();

				assertBreakLoc(procBreakLocVals.get(0), "[0]", main, 1,
					Set.of(TraceBreakpointKind.SW_EXECUTE),
					"ntdll!LdrInit");
				assertBreakLoc(procBreakLocVals.get(1), "[1]", main.add(4), 1,
					Set.of(TraceBreakpointKind.HW_EXECUTE),
					"ntdll!LdrInit");
			}
		}
	}

	@Test
	public void testRefreshBreakpoints2() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "all");

			RemoteMethod refreshProcWatchpoints = conn.getMethod("refresh_breakpoints");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				conn.execute("dbg = util.get_debugger()");
				conn.execute("pc = dbg.reg.get_pc()");
				conn.execute("dbg.ba(expr=pc, access=DbgEng.DEBUG_BREAK_EXECUTE)");
				conn.execute("dbg.ba(expr=pc+4, access=DbgEng.DEBUG_BREAK_READ)");
				conn.execute("dbg.ba(expr=pc+8, access=DbgEng.DEBUG_BREAK_WRITE)");
				TraceObject locations =
					Objects.requireNonNull(tb.objAny("Processes[].Breakpoints"));
				refreshProcWatchpoints.invoke(Map.of("node", locations));

				List<TraceObjectValue> procBreakVals = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0),
							PathPredicates.parse("Processes[].Breakpoints[]"))
						.map(p -> p.getLastEntry())
						.toList();
				assertEquals(3, procBreakVals.size());
				AddressRange rangeMain0 =
					procBreakVals.get(0).getChild().getValue(0, "_range").castValue();
				Address main0 = rangeMain0.getMinAddress();
				AddressRange rangeMain1 =
					procBreakVals.get(1).getChild().getValue(0, "_range").castValue();
				Address main1 = rangeMain1.getMinAddress();
				AddressRange rangeMain2 =
					procBreakVals.get(2).getChild().getValue(0, "_range").castValue();
				Address main2 = rangeMain2.getMinAddress();

				assertWatchLoc(procBreakVals.get(0), "[0]", main0, (int) rangeMain0.getLength(),
					Set.of(TraceBreakpointKind.HW_EXECUTE),
					"main");
				assertWatchLoc(procBreakVals.get(1), "[1]", main1, (int) rangeMain1.getLength(),
					Set.of(TraceBreakpointKind.WRITE),
					"main+4");
				assertWatchLoc(procBreakVals.get(2), "[2]", main2, (int) rangeMain1.getLength(),
					Set.of(TraceBreakpointKind.READ),
					"main+8");
			}
		}
	}

	@Test
	public void testRefreshProcesses() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, null);
			txCreate(conn, "Processes");
			txCreate(conn, "Processes[1]");

			RemoteMethod refreshProcesses = conn.getMethod("refresh_processes");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject processes = Objects.requireNonNull(tb.objAny("Processes"));

				refreshProcesses.invoke(Map.of("node", processes));

				// Would be nice to control / validate the specifics
				List<TraceObject> list = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0), PathPredicates.parse("Processes[]"))
						.map(p -> p.getDestination(null))
						.toList();
				assertEquals(1, list.size());
			}
		}
	}

	@Test
	public void testRefreshEnvironment() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Environment";
			start(conn, "notepad.exe");
			txPut(conn, "all");

			RemoteMethod refreshEnvironment = conn.getMethod("refresh_environment");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject env = Objects.requireNonNull(tb.objAny(path));

				refreshEnvironment.invoke(Map.of("node", env));

				// Assumes pydbg on Windows amd64
				assertEquals("pydbg", env.getValue(0, "_debugger").getValue());
				assertEquals("x86_64", env.getValue(0, "_arch").getValue());
				assertEquals("windows", env.getValue(0, "_os").getValue());
				assertEquals("little", env.getValue(0, "_endian").getValue());
			}
		}
	}

	@Test
	public void testRefreshThreads() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Threads";
			start(conn, "notepad.exe");
			txCreate(conn, path);

			RemoteMethod refreshThreads = conn.getMethod("refresh_threads");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject threads = Objects.requireNonNull(tb.objAny(path));

				refreshThreads.invoke(Map.of("node", threads));

				// Would be nice to control / validate the specifics
				int listSize = tb.trace.getThreadManager().getAllThreads().size();
				assertEquals(4, listSize);
			}
		}
	}

	@Test
	public void testRefreshStack() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Threads[].Stack";
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod refreshStack = conn.getMethod("refresh_stack");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				txPut(conn, "frames");
				TraceObject stack = Objects.requireNonNull(tb.objAny(path));
				refreshStack.invoke(Map.of("node", stack));

				// Would be nice to control / validate the specifics
				List<TraceObject> list = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0),
							PathPredicates.parse("Processes[].Threads[].Stack[]"))
						.map(p -> p.getDestination(null))
						.toList();
				assertTrue(list.size() > 1);
			}
		}
	}

	@Test
	public void testRefreshRegisters() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Threads[].Registers";
			start(conn, "notepad.exe");
			conn.execute("ghidra_trace_txstart('Tx')");
			conn.execute("ghidra_trace_putreg()");
			conn.execute("ghidra_trace_txcommit()");

			RemoteMethod refreshRegisters = conn.getMethod("refresh_registers");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				conn.execute("regs = util.get_debugger().reg");
				conn.execute("regs._set_register('rax', int(0xdeadbeef))");

				TraceObject registers = Objects.requireNonNull(tb.objAny(path, Lifespan.at(0)));
				refreshRegisters.invoke(Map.of("node", registers));

				long snap = 0;
				AddressSpace t1f0 = tb.trace.getBaseAddressFactory()
						.getAddressSpace(registers.getCanonicalPath().toString());
				TraceMemorySpace regs = tb.trace.getMemoryManager().getMemorySpace(t1f0, false);
				RegisterValue rax = regs.getValue(snap, tb.reg("rax"));
				assertEquals("deadbeef", rax.getUnsignedValue().toString(16));
			}
		}
	}

	@Test
	public void testRefreshMappings() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Memory";
			start(conn, "notepad.exe");
			txCreate(conn, path);

			RemoteMethod refreshMappings = conn.getMethod("refresh_mappings");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject memory = Objects.requireNonNull(tb.objAny(path));

				refreshMappings.invoke(Map.of("node", memory));

				// Would be nice to control / validate the specifics
				Collection<? extends TraceMemoryRegion> all =
					tb.trace.getMemoryManager().getAllRegions();
				assertThat(all.size(), greaterThan(2));
			}
		}
	}

	@Test
	public void testRefreshModules() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			String path = "Processes[].Modules";
			start(conn, "notepad.exe");
			txCreate(conn, path);

			RemoteMethod refreshModules = conn.getMethod("refresh_modules");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				TraceObject modules = Objects.requireNonNull(tb.objAny(path));

				refreshModules.invoke(Map.of("node", modules));

				// Would be nice to control / validate the specifics
				Collection<? extends TraceModule> all = tb.trace.getModuleManager().getAllModules();
				TraceModule modBash =
					Unique.assertOne(all.stream().filter(m -> m.getName().contains("notepad.exe")));
				assertNotEquals(tb.addr(0), Objects.requireNonNull(modBash.getBase()));
			}
		}
	}

	@Test
	public void testActivateThread() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod activateThread = conn.getMethod("activate_thread");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				txPut(conn, "threads");

				PathPattern pattern =
					PathPredicates.parse("Processes[].Threads[]").getSingletonPattern();
				List<TraceObject> list = tb.trace.getObjectManager()
						.getValuePaths(Lifespan.at(0), pattern)
						.map(p -> p.getDestination(null))
						.toList();
				assertEquals(4, list.size());

				for (TraceObject t : list) {
					activateThread.invoke(Map.of("thread", t));
					String out = conn.executeCapture("util.get_debugger().get_thread()");
					List<String> indices = pattern.matchKeys(t.getCanonicalPath().getKeyList());
					assertEquals(out, "%s".formatted(indices.get(1)));
				}
			}
		}
	}

	@Test
	public void testRemoveProcess() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "netstat.exe");
			txPut(conn, "processes");

			RemoteMethod removeProcess = conn.getMethod("remove_process");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/netstat.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				TraceObject proc2 = Objects.requireNonNull(tb.objAny("Processes[]"));
				removeProcess.invoke(Map.of("process", proc2));

				String out = conn.executeCapture("list(util.process_list())");
				assertThat(out, containsString("[]"));
			}
		}
	}

	@Test
	public void testAttachObj() throws Exception {
		try (DummyProc dproc = DummyProc.run("notepad.exe")) {
			try (PythonAndHandler conn = startAndConnectPython()) {
				start(conn, null);
				txPut(conn, "available");

				RemoteMethod attachObj = conn.getMethod("attach_obj");
				try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
					tb = new ToyDBTraceBuilder((Trace) mdo.get());
					TraceObject target =
						Objects.requireNonNull(tb.obj("Available[%d]".formatted(dproc.pid)));
					attachObj.invoke(Map.of("target", target));

					String out = conn.executeCapture("list(util.process_list())");
					assertThat(out, containsString("%d".formatted(dproc.pid)));
				}
			}
		}
	}

	@Test
	public void testAttachPid() throws Exception {
		try (DummyProc dproc = DummyProc.run("notepad.exe")) {
			try (PythonAndHandler conn = startAndConnectPython()) {
				start(conn, null);
				txPut(conn, "available");

				RemoteMethod attachPid = conn.getMethod("attach_pid");
				try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
					tb = new ToyDBTraceBuilder((Trace) mdo.get());
					Objects.requireNonNull(tb.objAny("Available["+dproc.pid+"]", Lifespan.at(0)));
					attachPid.invoke(Map.of("pid", dproc.pid));

					String out = conn.executeCapture("list(util.process_list())");
					assertThat(out, containsString("%d".formatted(dproc.pid)));
				}
			}
		}
	}

	@Test
	public void testDetach() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "netstat.exe");
			txPut(conn, "processes");

			RemoteMethod detach = conn.getMethod("detach");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/netstat.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				detach.invoke(Map.of("process", proc));

				String out = conn.executeCapture("list(util.process_list())");
				assertThat(out, containsString("[]"));
			}
		}
	}

	@Test
	public void testLaunchEntry() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, null);
			txPut(conn, "processes");

			RemoteMethod launch = conn.getMethod("launch_loader");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				launch.invoke(Map.ofEntries(
				    Map.entry("file", "notepad.exe")));

				String out = conn.executeCapture("list(util.process_list())");
				assertThat(out, containsString("notepad.exe"));
			}
		}
	}

	@Test //Can't do this test because create(xxx, initial_break=False) doesn't return
	public void testLaunch() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, null);
			txPut(conn, "processes");

			RemoteMethod launch = conn.getMethod("launch");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/noname")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				launch.invoke(Map.ofEntries(
					Map.entry("timeout", 1L),
					Map.entry("file", "notepad.exe")));

				txPut(conn, "processes");

				String out = conn.executeCapture("list(util.process_list())");
				assertThat(out, containsString("notepad.exe"));
			}
		}
	}

	@Test 
	public void testKill() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod kill = conn.getMethod("kill");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				kill.invoke(Map.of("process", proc));

				String out = conn.executeCapture("list(util.process_list())");
				assertThat(out, containsString("[]"));
			}
		}
	}

	@Test
	public void testStepInto() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod step_into = conn.getMethod("step_into");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();
				txPut(conn, "threads");

				TraceObject thread = Objects.requireNonNull(tb.objAny("Processes[].Threads[]"));

				while (!getInst(conn).contains("call")) {
					step_into.invoke(Map.of("thread", thread));
				}

				String disCall = getInst(conn);
				// lab0:
				//    -> addr0
				// 
				// lab1:
				//    addr1
				String[] split = disCall.split("\\s+");  // get target
  				long pcCallee = Long.decode(split[split.length-1]);

				step_into.invoke(Map.of("thread", thread));
				long pc = getAddressAtOffset(conn, 0);
				assertEquals(pcCallee, pc);
			}
		}
	}

	@Test
	public void testStepOver() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod step_over = conn.getMethod("step_over");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();
				txPut(conn, "threads");

				TraceObject thread = Objects.requireNonNull(tb.objAny("Processes[].Threads[]"));

				while (!getInst(conn).contains("call")) {
					step_over.invoke(Map.of("thread", thread));
				}

				String disCall = getInst(conn);
				String[] split = disCall.split("\\s+");  // get target
  				long pcCallee = Long.decode(split[split.length-1]);

				step_over.invoke(Map.of("thread", thread));
				long pc = getAddressAtOffset(conn, 0);
				assertNotEquals(pcCallee, pc);
			}
		}
	}

	@Test 
	public void testStepTo() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");

			RemoteMethod step_into = conn.getMethod("step_into");
			RemoteMethod step_to = conn.getMethod("step_to");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				txPut(conn, "threads");

				TraceObject thread = Objects.requireNonNull(tb.objAny("Processes[].Threads[]"));
				while (!getInst(conn).contains("call")) {
					step_into.invoke(Map.of("thread", thread));
				}
				step_into.invoke(Map.of("thread", thread));
				
				int sz = Integer.parseInt(getInstSizeAtOffset(conn, 0));
				for (int i = 0; i < 4; i++) {
					sz += Integer.parseInt(getInstSizeAtOffset(conn, sz));
				}

				long pcNext = getAddressAtOffset(conn, sz);

				boolean success = (boolean) step_to.invoke(Map.of("thread", thread, "address", tb.addr(pcNext), "max", 10));
				assertTrue(success);
				
				long pc = getAddressAtOffset(conn, 0);
				assertEquals(pcNext, pc);
			}
		}
	}

	@Test
	public void testStepOut() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod step_into = conn.getMethod("step_into");
			RemoteMethod step_out = conn.getMethod("step_out");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();
				txPut(conn, "threads");

				TraceObject thread = Objects.requireNonNull(tb.objAny("Processes[].Threads[]"));

				while (!getInst(conn).contains("call")) {
					step_into.invoke(Map.of("thread", thread));
				}

				int sz = Integer.parseInt(getInstSizeAtOffset(conn, 0));
				long pcNext = getAddressAtOffset(conn, sz);

				step_into.invoke(Map.of("thread", thread));
				step_out.invoke(Map.of("thread", thread));
				long pc = getAddressAtOffset(conn, 0);
				assertEquals(pcNext, pc);
			}
		}
	}

	@Test
	public void testBreakAddress() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakAddress = conn.getMethod("break_address");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				
				long address = getAddressAtOffset(conn, 0);				
				breakAddress.invoke(Map.of("process", proc, "address", tb.addr(address)));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString(Long.toHexString(address)));
			}
		}
	}

	@Test
	public void testBreakExpression() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakExpression = conn.getMethod("break_expression");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				breakExpression.invoke(Map.of("expression", "entry"));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("entry"));
			}
		}
	}

	@Test  
	public void testBreakHardwareAddress() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakAddress = conn.getMethod("break_hw_address");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				
				long address = getAddressAtOffset(conn, 0);				
				breakAddress.invoke(Map.of("process", proc, "address", tb.addr(address)));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString(Long.toHexString(address)));
			}
		}
	}

	@Test
	public void testBreakHardwareExpression() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakExpression = conn.getMethod("break_hw_expression");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				breakExpression.invoke(Map.of("expression", "entry"));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("entry"));
			}
		}
	}

	@Test
	public void testBreakReadRange() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakRange = conn.getMethod("break_read_range");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				long address = getAddressAtOffset(conn, 0);				
				AddressRange range = tb.range(address, address + 3); // length 4
				breakRange.invoke(Map.of("process", proc, "range", range));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("%x".formatted(address)));
				assertThat(out, containsString("sz=4"));
				assertThat(out, containsString("type=r"));
			}
		}
	}

	@Test
	public void testBreakReadExpression() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakExpression = conn.getMethod("break_read_expression");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				breakExpression.invoke(Map.of("expression", "ntdll!LdrInitShimEngineDynamic"));
				long address = getAddressAtOffset(conn, 0);				

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString(Long.toHexString(address>>24)));
				assertThat(out, containsString("sz=1"));
				assertThat(out, containsString("type=r"));
			}
		}
	}

	@Test
	public void testBreakWriteRange() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakRange = conn.getMethod("break_write_range");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				long address = getAddressAtOffset(conn, 0);				
				AddressRange range = tb.range(address, address + 3); // length 4
				breakRange.invoke(Map.of("process", proc, "range", range));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("%x".formatted(address)));
				assertThat(out, containsString("sz=4"));
				assertThat(out, containsString("type=w"));
			}
		}
	}

	@Test
	public void testBreakWriteExpression() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakExpression = conn.getMethod("break_write_expression");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				breakExpression.invoke(Map.of("expression", "ntdll!LdrInitShimEngineDynamic"));
				long address = getAddressAtOffset(conn, 0);				

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString(Long.toHexString(address>>24)));
				assertThat(out, containsString("sz=1"));
				assertThat(out, containsString("type=w"));
			}
		}
	}

	@Test
	public void testBreakAccessRange() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakRange = conn.getMethod("break_access_range");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
				waitStopped();

				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				long address = getAddressAtOffset(conn, 0);				
				AddressRange range = tb.range(address, address + 3); // length 4
				breakRange.invoke(Map.of("process", proc, "range", range));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("%x".formatted(address)));
				assertThat(out, containsString("sz=4"));
				assertThat(out, containsString("type=rw"));
			}
		}
	}

	@Test
	public void testBreakAccessExpression() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakExpression = conn.getMethod("break_access_expression");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				breakExpression.invoke(Map.of("expression", "ntdll!LdrInitShimEngineDynamic"));
				long address = getAddressAtOffset(conn, 0);				

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString(Long.toHexString(address>>24)));
				assertThat(out, containsString("sz=1"));
				assertThat(out, containsString("type=rw"));
			}
		}
	}

	@Test
	public void testToggleBreakpoint() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakAddress = conn.getMethod("break_address");
			RemoteMethod toggleBreakpoint = conn.getMethod("toggle_breakpoint");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());

				long address = getAddressAtOffset(conn, 0);				
				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				breakAddress.invoke(Map.of("process", proc, "address", tb.addr(address)));

				txPut(conn, "breakpoints");
				TraceObject bpt = Objects.requireNonNull(tb.objAny("Processes[].Breakpoints[]"));

				toggleBreakpoint.invoke(Map.of("breakpoint", bpt, "enabled", false));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("disabled"));
			}
		}
	}

	@Test
	public void testDeleteBreakpoint() throws Exception {
		try (PythonAndHandler conn = startAndConnectPython()) {
			start(conn, "notepad.exe");
			txPut(conn, "processes");

			RemoteMethod breakAddress = conn.getMethod("break_address");
			RemoteMethod deleteBreakpoint = conn.getMethod("delete_breakpoint");
			try (ManagedDomainObject mdo = openDomainObject("/New Traces/pydbg/notepad.exe")) {
				tb = new ToyDBTraceBuilder((Trace) mdo.get());
			
				long address = getAddressAtOffset(conn, 0);				
				TraceObject proc = Objects.requireNonNull(tb.objAny("Processes[]"));
				breakAddress.invoke(Map.of("process", proc, "address", tb.addr(address)));

				txPut(conn, "breakpoints");
				TraceObject bpt = Objects.requireNonNull(tb.objAny("Processes[].Breakpoints[]"));

				deleteBreakpoint.invoke(Map.of("breakpoint", bpt));

				String out = conn.executeCapture("list(util.get_breakpoints())");
				assertThat(out, containsString("[]"));
			}
		}
	}

	private void start(PythonAndHandler conn, String obj) {
		conn.execute("from ghidradbg.commands import *");
		if (obj != null)
			conn.execute("ghidra_trace_create('"+obj+"')");
		else 
			conn.execute("ghidra_trace_create()");	}

	private void txPut(PythonAndHandler conn, String obj) {
		conn.execute("ghidra_trace_txstart('Tx')");
		conn.execute("ghidra_trace_put_" + obj + "()");
		conn.execute("ghidra_trace_txcommit()");
	}

	private void txCreate(PythonAndHandler conn, String path) {
		conn.execute("ghidra_trace_txstart('Fake')");
		conn.execute("ghidra_trace_create_obj('%s')".formatted(path));
		conn.execute("ghidra_trace_txcommit()");
	}

	private String getInst(PythonAndHandler conn) {
		return getInstAtOffset(conn, 0);
	}

	private String getInstAtOffset(PythonAndHandler conn, int offset) {
		String inst = "util.get_inst(util.get_debugger().reg.get_pc()+"+offset+")";
		String ret = conn.executeCapture(inst);
		return ret.substring(1, ret.length()-1);    // remove <>
	}

	private String getInstSizeAtOffset(PythonAndHandler conn, int offset) {
		String instSize = "util.get_inst_sz(util.get_debugger().reg.get_pc()+"+offset+")";
		return conn.executeCapture(instSize);
	}

	private long getAddressAtOffset(PythonAndHandler conn, int offset) {
		String inst = "util.get_inst(util.get_debugger().reg.get_pc()+"+offset+")";
		String ret = conn.executeCapture(inst);
		String[] split = ret.split("\\s+");  // get target
		return Long.decode(split[1]);
	}

}
